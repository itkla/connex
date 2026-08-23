'use client';

import {
    Fragment,
    useCallback,
    useEffect,
    useMemo,
    useRef,
    useState,
    useSyncExternalStore,
    type ReactNode,
} from 'react';
import {
    ArchiveBoxIcon,
    ArrowLeftIcon,
    ArrowPathIcon,
    ArrowRightStartOnRectangleIcon,
    ArrowDownIcon,
    ArrowUpIcon,
    CheckIcon,
    ClockIcon,
    EllipsisHorizontalIcon,
    ExclamationCircleIcon,
    ExclamationTriangleIcon,
    LinkIcon,
    MagnifyingGlassIcon,
    PaperClipIcon,
    PencilSquareIcon,
    PlusIcon,
    SparklesIcon,
    ArrowsPointingOutIcon,
    StopCircleIcon,
    StopIcon,
    UserGroupIcon,
    UserPlusIcon,
    UserIcon,
    XMarkIcon,
} from '@heroicons/react/24/outline';
import Link from 'next/link';
import { useFormatter } from 'next-intl';
import { createPortal } from 'react-dom';
import { motion, useReducedMotion } from 'motion/react';

import AccessDenied from '@/app/components/AccessDenied';
import { EmptyState } from '@/app/components/EmptyState';
import ErrorState from '@/app/components/ErrorState';
import SectionBoundary from '@/app/components/SectionBoundary';
import MentionEditor from '@/app/components/activity/notes/MentionEditor';
import AskConnexAnswerDocument, {
    AskConnexCheckedTrail,
    type AskConnexAnswerDocumentLabels,
} from '@/app/components/ask-connex/AskConnexAnswerDocument';
import {
    AskConnexContextStrip,
    AskConnexScopeNotice,
    type AskConnexContextLabels,
} from '@/app/components/ask-connex/AskConnexContextCockpit';
import AskConnexTab from '@/app/components/ask-connex/AskConnexTab';
import AskConnexToolCard, {
    type AskConnexToolCardLabels,
} from '@/app/components/ask-connex/AskConnexToolCard';
import type {
    AskConnexAttachment,
    AskConnexFileAttachment,
    AskConnexScopePreview,
    AskConnexSelectionContext,
    AskConnexToolAction,
    AskConnexToolCardState,
    AskConnexTurnState,
} from '@/app/lib/askConnex';
import {
    anchorAskConnexToolCards,
    askConnexCitationHref,
    askConnexCitations,
    askConnexScopeNeedsConfirmation,
    askConnexScopePreviewKey,
    askConnexTranscript,
    groupAskConnexMessages,
    hasPendingAskConnexFileOperation,
    latestAskConnexSuggestions,
} from '@/app/lib/askConnex';
import type { AskConnexStreamStore } from '@/app/lib/askConnexStream';
import { easeOut, instant, springSmooth } from '@/app/lib/motion';
import type {
    AiChatCitation,
    AiChatMessage,
    AiChatParticipant,
    AiChatPresence,
    AiChatProgressItem,
    AiChatSession,
    WorkspaceMember,
} from '@/app/lib/types';
import { Avatar, AvatarFallback, AvatarGroup, AvatarImage } from '@/components/ui/avatar';
import { Button } from '@/components/ui/button';
import { IconButton } from '@/components/ui/icon-button';
import {
    Dialog,
    DialogContent,
    DialogDescription,
    DialogFooter,
    DialogHeader,
    DialogTitle,
} from '@/components/ui/dialog';
import {
    Drawer,
    DrawerContent,
    DrawerDescription,
    DrawerTitle,
} from '@/components/ui/drawer';
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuLabel,
    DropdownMenuSeparator,
    DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { Input } from '@/components/ui/input';
import {
    Message,
    MessageAvatar,
    MessageContent,
    MessageFooter,
    MessageGroup,
    MessageHeader,
} from '@/components/ui/message';
import {
    MessageScroller,
    MessageScrollerButton,
    MessageScrollerContent,
    MessageScrollerItem,
    MessageScrollerProvider,
    MessageScrollerViewport,
} from '@/components/ui/message-scroller';
import {
    Select,
    SelectContent,
    SelectItem,
    SelectTrigger,
    SelectValue,
} from '@/components/ui/select';
import { Skeleton } from '@/components/ui/skeleton';
import { cn } from '@/lib/utils';

type DrawerLoadState = 'loading' | 'ready' | 'error' | 'forbidden';

const ASK_CONNEX_MENTION_TYPES = {
    '@': ['person'],
    '#': ['company', 'deal'],
} as const;

type UnavailableState = {
    title: string;
    body: string;
} | null;

/**
 * The vocabulary the live and settled answer surfaces need: the streamed tail, its partial-answer
 * disclosure, the status line, and the milestone trail beneath it.
 */
export type AskConnexTurnLabels = {
    answerDocument: AskConnexAnswerDocumentLabels;
    assistantAuthor: string;
    budgetExhausted: string;
    toolResultBudgetExhausted: string;
    partialAnswer: string;
    retry: string;
    stop: string;
    stopping: string;
    turnAccepted: string;
    turnCancelled: string;
    turnFailed: string;
    turnImageUnsupported: string;
    turnResolved: string;
    turnStreaming: string;
    turnTimedOut: string;
    turnWorking: string;
};

type AskConnexDrawerLabels = AskConnexContextLabels & AskConnexTurnLabels & {
    addContext: string;
    addRecordContext: string;
    archive: string;
    attachFile: string;
    citations: string;
    disclosureCreation: string;
    disclosureList: string;
    imageDisclosure: string;
    citationKind: (kind: AiChatCitation['kind']) => string;
    close: string;
    closeWorkspace: string;
    composerAria: string;
    composerHint: string;
    composerPlaceholder: string;
    emptyBody: string;
    emptyTitle: string;
    formerMember: string;
    contentWithheld: string;
    historySummarized: string;
    invitation: string;
    invitations: string;
    invite: string;
    inviteMember: string;
    invitePending: string;
    join: string;
    leave: string;
    manageSharing: string;
    memberAuthor: (id: number) => string;
    jumpToLatest: string;
    loadError: string;
    messages: string;
    newChat: string;
    noRecentSessions: string;
    noMatchingSessions: string;
    moreOptions: string;
    participants: string;
    presence: string;
    recentSessions: string;
    searchSessions: string;
    removeParticipant: (name: string) => string;
    rename: string;
    renameCancel: string;
    renameDescription: string;
    renameLabel: string;
    renameSave: string;
    renameSaving: string;
    renameTitle: string;
    send: string;
    shareCancel: string;
    shareConfirm: string;
    shareDescription: string;
    shared: string;
    shareTitle: string;
    suggestedFollowUps: string;
    title: string;
    tooLong: string;
    typing: (names: string) => string;
    unshare: string;
    openWorkspace: string;
    toolCard: AskConnexToolCardLabels;
};

type AskConnexDrawerProps = {
    open: boolean;
    instantOpen: boolean;
    isMobile: boolean;
    showTab: boolean;
    workspace: boolean;
    desktopRoot: HTMLElement | null;
    workspaceRoot: HTMLElement | null;
    sessions: AiChatSession[];
    invitations: AiChatSession[];
    activeSession: AiChatSession | null;
    participants: AiChatParticipant[];
    presence: AiChatPresence | null;
    members: WorkspaceMember[];
    canShare: boolean;
    messages: AiChatMessage[];
    freshMessageIds: ReadonlySet<number>;
    loadState: DrawerLoadState;
    loadError: Error | null;
    composer: string;
    implicitContext: AskConnexAttachment | null;
    selectionContext: AskConnexSelectionContext | null;
    unsupportedPageContext: { type: AskConnexSelectionContext['type']; label: string } | null;
    pinnedContext: readonly AskConnexAttachment[];
    pageContextPinned: boolean;
    contextCorrected: boolean;
    scopePreview: AskConnexScopePreview | null;
    attachments: AskConnexAttachment[];
    fileAttachments: AskConnexFileAttachment[];
    canAttachFiles: boolean;
    canRemoveFiles: boolean;
    contextOverflow: boolean;
    contentTooLong: boolean;
    working: boolean;
    turn: AskConnexTurnState;
    streamStore: AskConnexStreamStore;
    streaming: boolean;
    cancelling: boolean;
    toolCalls: AskConnexToolCardState[];
    actionableToolCallIds: ReadonlySet<number>;
    canRetryTurn: boolean;
    unavailable: UnavailableState;
    starterPrompts: string[];
    labels: AskConnexDrawerLabels;
    onOpenChange: (open: boolean) => void;
    onOpenChangeComplete: (open: boolean) => void;
    onKeyboardClose: () => void;
    onOpenWorkspace: () => void;
    onCloseWorkspace: () => void;
    onSelectSession: (session: AiChatSession) => void;
    onNewChat: () => void;
    onRename: (title: string) => Promise<boolean>;
    onArchive: () => void;
    onJoinInvitation: (session: AiChatSession) => void;
    onShare: (shared: boolean) => Promise<boolean>;
    onInvite: (userId: number) => Promise<boolean>;
    onLeave: () => void;
    onRemoveParticipant: (userId: number) => void;
    onRetry: () => void;
    onComposerChange: (value: string) => void;
    onRemoveAttachment: (attachment: AskConnexAttachment) => void;
    onTogglePagePin: () => void;
    onUnpinContext: (attachment: AskConnexAttachment) => void;
    onRemovePageContext: () => void;
    onRemoveSelectionContext: () => void;
    onResetContext: () => void;
    onAttachFiles: (files: File[]) => void;
    onRemoveFileAttachment: (attachment: AskConnexFileAttachment) => void;
    onSend: (content?: string) => void;
    onCancelTurn: () => void;
    onRetryTurn: () => void;
    onToolAction: (toolCallId: number, action: AskConnexToolAction) => void;
};

type ConversationSurfaceProps = Omit<
    AskConnexDrawerProps,
    'instantOpen'
    | 'isMobile'
    | 'showTab'
    | 'desktopRoot'
    | 'workspaceRoot'
    | 'onOpenChange'
    | 'onOpenChangeComplete'
    | 'onKeyboardClose'
    | 'onRename'
> & {
    closeButton: ReactNode;
    onBeginRename: () => void;
    onManageSharing: () => void;
};

function TranscriptSkeleton() {
    return (
        <div className="space-y-5 px-4 py-6">
            <div className="ml-auto w-3/4 space-y-2 rounded-2xl bg-primary p-3">
                <Skeleton className="h-3 w-4/5 bg-primary-foreground/30" />
                <Skeleton className="h-3 w-2/5 bg-primary-foreground/30" />
            </div>
            <div className="w-4/5 space-y-2 rounded-2xl bg-muted p-3">
                <Skeleton className="h-3 w-full" />
                <Skeleton className="h-3 w-11/12" />
                <Skeleton className="h-3 w-3/4" />
            </div>
        </div>
    );
}

function MessageCitations({
    citations,
    labels,
}: {
    citations: AiChatCitation[] | null | undefined;
    labels: AskConnexDrawerLabels;
}) {
    const visible = askConnexCitations(citations);
    if (visible.length === 0) return null;

    return (
        <ul aria-label={labels.citations} className="flex flex-wrap gap-1.5">
            {visible.map((citation) => (
                <li key={`${citation.kind}:${citation.id}`}>
                    <Link
                        href={askConnexCitationHref(citation)}
                        className="inline-flex max-w-56 items-center gap-1.5 rounded-full border border-border px-2.5 py-1 text-xs text-muted-foreground transition-colors hover:bg-muted hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
                    >
                        <LinkIcon className="size-3 shrink-0" />
                        <span className="truncate">{citation.label ?? labels.citationKind(citation.kind)}</span>
                    </Link>
                </li>
            ))}
        </ul>
    );
}

function MessageSuggestions({
    suggestions,
    label,
    onSend,
}: {
    suggestions: string[];
    label: string;
    onSend: (content?: string) => void;
}) {
    if (suggestions.length === 0) return null;

    return (
        <ul aria-label={label} className="flex flex-wrap gap-1.5">
            {suggestions.map((suggestion) => (
                <li key={suggestion}>
                    <Button
                        type="button"
                        variant="outline"
                        size="inline"
                        className="h-auto max-w-72 justify-start whitespace-normal py-1.5 text-left leading-4"
                        onClick={() => onSend(suggestion)}
                    >
                        {suggestion}
                    </Button>
                </li>
            ))}
        </ul>
    );
}

function HistorySummaryMarker({ label }: { label: string }) {
    return (
        <div role="note" className="flex items-center gap-3 py-1 text-xs text-muted-foreground">
            <span aria-hidden className="h-px flex-1 bg-border" />
            <span>{label}</span>
            <span aria-hidden className="h-px flex-1 bg-border" />
        </div>
    );
}

function SenderAvatar({ user, label }: { user: boolean; label: string }) {
    const initial = label.trim().slice(0, 1).toLocaleUpperCase();
    return (
        <MessageAvatar aria-hidden>
            <Avatar size="sm">
                <AvatarFallback className={user ? 'bg-primary text-primary-foreground' : undefined}>
                    {user
                        ? initial || <UserIcon className="size-3.5" />
                        : <SparklesIcon className="size-3.5" />}
                </AvatarFallback>
            </Avatar>
        </MessageAvatar>
    );
}

function TranscriptMessage({
    message,
    fresh,
    firstInGroup,
    lastInGroup,
    suggestions,
    toolCalls,
    actionableToolCallIds,
    labels,
    onSend,
    onToolAction,
}: {
    message: AiChatMessage;
    fresh: boolean;
    firstInGroup: boolean;
    lastInGroup: boolean;
    suggestions: string[];
    toolCalls: AskConnexToolCardState[];
    actionableToolCallIds: ReadonlySet<number>;
    labels: AskConnexDrawerLabels;
    onSend: (content?: string) => void;
    onToolAction: (toolCallId: number, action: AskConnexToolAction) => void;
}) {
    const format = useFormatter();
    const reduceMotion = useReducedMotion() ?? false;
    const user = message.authorKind === 'user';
    const author = user
        ? message.authorDisplayName
            ?? (message.authorUserId === null ? labels.formerMember : labels.memberAuthor(message.authorUserId))
        : labels.assistantAuthor;
    const animateEntrance = fresh && !user;
    const createdAt = new Date(message.createdAt);
    const timestamp = Number.isNaN(createdAt.getTime())
        ? null
        : format.dateTime(createdAt, { hour: 'numeric', minute: '2-digit' });

    return (
        <Message align={user ? 'end' : 'start'}>
            {lastInGroup
                ? <SenderAvatar user={user} label={author} />
                : <MessageAvatar aria-hidden className="bg-transparent" />}
            <MessageContent className={cn(
                'w-auto gap-1.5',
                !user && message.answerDocument ? 'max-w-full' : 'max-w-[85%]',
            )}>
                {firstInGroup ? (
                    <MessageHeader className={user ? 'justify-end' : undefined}>
                        {author}
                    </MessageHeader>
                ) : null}
                <motion.div
                    initial={animateEntrance ? (reduceMotion ? { opacity: 0 } : { opacity: 0, transform: 'translateY(0.375rem)' }) : false}
                    animate={{ opacity: 1, transform: 'translateY(0rem)' }}
                    transition={reduceMotion ? instant : { duration: 0.2, ease: easeOut }}
                    className={cn(
                        message.answerDocument
                            ? 'w-full'
                            : 'whitespace-pre-wrap break-words rounded-2xl px-3.5 py-2.5 text-sm leading-relaxed',
                        message.contentWithheld === true
                            ? 'bg-muted text-muted-foreground italic'
                            : user
                            ? 'bg-primary text-primary-foreground'
                            : message.answerDocument
                                ? null
                                : 'bg-muted text-foreground',
                    )}
                >
                    {message.contentWithheld === true
                        ? labels.contentWithheld
                        : !user && message.answerDocument
                            ? (
                                <AskConnexAnswerDocument
                                    document={message.answerDocument}
                                    labels={labels.answerDocument}
                                />
                            )
                            : message.content}
                </motion.div>
                {!user && message.contentWithheld !== true && !message.answerDocument
                    ? <MessageCitations citations={message.citations} labels={labels} />
                    : null}
                {!user && message.contentWithheld !== true ? (
                    <ToolCallCards
                        cards={toolCalls}
                        labels={labels.toolCard}
                        actionsDisabled={false}
                        actionableToolCallIds={actionableToolCallIds}
                        onAction={onToolAction}
                    />
                ) : null}
                {!user && message.contentWithheld !== true ? (
                    <MessageSuggestions
                        suggestions={suggestions}
                        label={labels.suggestedFollowUps}
                        onSend={onSend}
                    />
                ) : null}
                {lastInGroup && timestamp ? (
                    <MessageFooter className="px-1 font-normal">
                        <time dateTime={message.createdAt}>{timestamp}</time>
                    </MessageFooter>
                ) : null}
            </MessageContent>
        </Message>
    );
}

function ToolCallCards({
    cards,
    labels,
    actionsDisabled,
    actionableToolCallIds,
    onAction,
}: {
    cards: AskConnexToolCardState[];
    labels: AskConnexToolCardLabels;
    actionsDisabled: boolean;
    actionableToolCallIds: ReadonlySet<number>;
    onAction: (toolCallId: number, action: AskConnexToolAction) => void;
}) {
    if (cards.length === 0) return null;
    return (
        <div className="space-y-2">
            {cards.map((card) => (
                <AskConnexToolCard
                    key={card.id}
                    card={card}
                    labels={labels}
                    actionsDisabled={actionsDisabled || !actionableToolCallIds.has(card.id)}
                    onAction={onAction}
                />
            ))}
        </div>
    );
}

function PresenceStrip({ presence, labels }: { presence: AiChatPresence; labels: AskConnexDrawerLabels }) {
    const typingUserIds = new Set(presence.typingUserIds);
    const typingNames: string[] = [];
    for (const participant of presence.present) {
        if (!participant.currentUser && typingUserIds.has(participant.userId)) {
            typingNames.push(participant.displayName);
        }
    }

    return (
        <div className="flex min-h-10 shrink-0 items-center gap-2 border-b border-border px-4 py-2">
            <span className="text-xs font-medium text-muted-foreground">{labels.presence}</span>
            <AvatarGroup aria-label={labels.presence}>
                {presence.present.map((participant) => (
                    <Avatar key={participant.userId} size="sm" title={participant.displayName}>
                        <AvatarImage src={participant.profilePictureUrl ?? undefined} alt="" />
                        <AvatarFallback>{participant.displayName.slice(0, 1).toUpperCase()}</AvatarFallback>
                    </Avatar>
                ))}
            </AvatarGroup>
            {typingNames.length > 0 ? (
                <span role="status" className="min-w-0 truncate text-xs text-muted-foreground">
                    {labels.typing(typingNames.join(', '))}
                </span>
            ) : null}
        </div>
    );
}

/**
 * A settled answer that produced no transcript message: what happened, what it covered before it
 * stopped, and the one action worth offering next.
 *
 * The milestone trail is repeated here because it otherwise exists only while the answer is still
 * running — a reader who arrives after it stopped would have no way to learn what was covered. The
 * announcement is deliberately kept to the one-line outcome: the trail and the retry control sit
 * outside it so settling does not read a whole list aloud.
 */
function SettledTurnActivity({
    icon,
    message,
    destructive,
    progress,
    labels,
    canRetry,
    onRetry,
}: {
    icon: ReactNode;
    message: string;
    destructive?: boolean;
    progress: AiChatProgressItem[];
    labels: AskConnexTurnLabels;
    canRetry: boolean;
    onRetry: () => void;
}) {
    return (
        <div className="space-y-2 px-4 py-2 text-xs text-muted-foreground">
            <div
                role="status"
                className={cn('flex items-center gap-2', destructive && 'text-destructive')}
            >
                {icon}
                <span>{message}</span>
            </div>
            <AskConnexCheckedTrail progress={progress} labels={labels.answerDocument} />
            {canRetry ? (
                <Button type="button" variant="outline" size="inline" onClick={onRetry}>
                    <ArrowPathIcon />
                    {labels.retry}
                </Button>
            ) : null}
        </div>
    );
}

/**
 * The live and settled state of the answer in progress.
 *
 * While the answer runs, the whole region is the announced status so each new milestone is spoken
 * as it lands rather than only the overall phase; the streamed words themselves are never announced.
 *
 * The stop control appears only for the member who asked. A participant who opened a shared session
 * mid-turn adopts that turn into the same state, and the cancellation endpoint rejects anyone but
 * its requester, so offering the control to them would be a button that can only fail.
 */
export function TurnActivity({
    turn,
    streaming,
    cancelling,
    canRetry,
    labels,
    onCancel,
    onRetry,
}: {
    turn: AskConnexTurnState;
    streaming: boolean;
    cancelling: boolean;
    canRetry: boolean;
    labels: AskConnexTurnLabels;
    onCancel: () => void;
    onRetry: () => void;
}) {
    if (turn.phase === 'idle') return null;
    if (turn.phase === 'resolved') {
        return (
            <div role="status" className="flex items-center gap-2 px-4 py-2 text-xs text-muted-foreground">
                <CheckIcon className="size-3.5 text-primary" />
                <span>{labels.turnResolved}</span>
            </div>
        );
    }
    if (turn.phase === 'failed') {
        return (
            <SettledTurnActivity
                icon={<ExclamationCircleIcon className="size-3.5" />}
                destructive
                message={turn.reason === 'image_input_unsupported'
                    ? labels.turnImageUnsupported
                    : turn.reason === 'tool_result_budget_exhausted'
                        ? labels.toolResultBudgetExhausted
                        : turn.reason === 'budget_exhausted'
                            ? labels.budgetExhausted
                            : labels.turnFailed}
                progress={turn.progress}
                labels={labels}
                canRetry={canRetry}
                onRetry={onRetry}
            />
        );
    }
    if (turn.phase === 'timed_out') {
        return (
            <SettledTurnActivity
                icon={<ClockIcon className="size-3.5" />}
                message={labels.turnTimedOut}
                progress={turn.progress}
                labels={labels}
                canRetry={canRetry}
                onRetry={onRetry}
            />
        );
    }
    if (turn.phase === 'cancelled') {
        return (
            <SettledTurnActivity
                icon={<StopCircleIcon className="size-3.5" />}
                message={labels.turnCancelled}
                progress={turn.progress}
                labels={labels}
                canRetry={false}
                onRetry={onRetry}
            />
        );
    }
    return (
        <div role="status" className="space-y-2 px-4 py-2 text-xs text-muted-foreground">
            <div className="flex items-center gap-2">
                <SparklesIcon className="size-3.5" />
                <span>{cancelling
                    ? labels.stopping
                    : turn.phase === 'accepted'
                        ? labels.turnAccepted
                        : streaming
                            ? labels.turnStreaming
                            : labels.turnWorking}</span>
                {turn.cancellable ? (
                    <IconButton
                        type="button"
                        variant="ghost"
                        size="icon-toolbar"
                        label={labels.stop}
                        disabled={cancelling}
                        onClick={onCancel}
                    >
                        <StopIcon className="size-3.5" />
                    </IconButton>
                ) : null}
            </div>
            <AskConnexCheckedTrail
                progress={turn.progress}
                labels={labels.answerDocument}
                expanded
            />
        </div>
    );
}

/**
 * The words the assistant has written so far, and — once the answer stops without resolving — the
 * words it had written when it stopped.
 *
 * A settled answer that never resolved leaves no transcript message behind, so this tail is the
 * only place its partial text exists. It stays on screen and says plainly that it is unfinished
 * rather than disappearing and leaving a one-line failure where an answer had been forming.
 */
export function StreamingTail({
    store,
    turn,
    labels,
}: {
    store: AskConnexStreamStore;
    turn: AskConnexTurnState;
    labels: AskConnexTurnLabels;
}) {
    const snapshot = useSyncExternalStore(store.subscribe, store.getSnapshot, () => null);
    if (snapshot === null || snapshot.text.length === 0 || snapshot.turnId !== turn.turnId) {
        return null;
    }
    const live = turn.phase === 'accepted' || turn.phase === 'running';

    return (
        <MessageScrollerItem messageId="streaming-tail" className="px-4 pt-5 last:pb-5">
            <MessageGroup>
                <Message align="start">
                    <SenderAvatar user={false} label={labels.assistantAuthor} />
                    <MessageContent className="w-auto max-w-[85%] gap-1.5">
                        <MessageHeader>{labels.assistantAuthor}</MessageHeader>
                        <div className="whitespace-pre-wrap break-words rounded-2xl bg-muted px-3.5 py-2.5 text-sm leading-relaxed text-foreground">
                            {snapshot.text}
                            {live ? (
                                <span
                                    aria-hidden
                                    className="ml-px inline-block h-[1em] w-0.5 translate-y-[0.125em] rounded-full bg-foreground/70 animate-caret-blink motion-reduce:animate-none"
                                />
                            ) : null}
                        </div>
                        {live ? null : (
                            <p className="flex items-start gap-1.5 px-1 text-xs text-muted-foreground">
                                <ExclamationTriangleIcon aria-hidden className="mt-0.5 size-3.5 shrink-0" />
                                <span>{labels.partialAnswer}</span>
                            </p>
                        )}
                    </MessageContent>
                </Message>
            </MessageGroup>
        </MessageScrollerItem>
    );
}


function SessionMenu({
    sessions,
    invitations,
    activeSession,
    canShare,
    working,
    labels,
    onSelectSession,
    onNewChat,
    onBeginRename,
    onArchive,
    onJoinInvitation,
    onManageSharing,
    onLeave,
}: {
    sessions: AiChatSession[];
    invitations: AiChatSession[];
    activeSession: AiChatSession | null;
    canShare: boolean;
    working: boolean;
    labels: AskConnexDrawerLabels;
    onSelectSession: (session: AiChatSession) => void;
    onNewChat: () => void;
    onBeginRename: () => void;
    onArchive: () => void;
    onJoinInvitation: (session: AiChatSession) => void;
    onManageSharing: () => void;
    onLeave: () => void;
}) {
    return (
        <DropdownMenu>
            <DropdownMenuTrigger asChild>
                <IconButton variant="ghost" size="icon-toolbar" label={labels.moreOptions}>
                    <EllipsisHorizontalIcon className="size-4" />
                </IconButton>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="w-72">
                <DropdownMenuLabel>{labels.recentSessions}</DropdownMenuLabel>
                <p className="px-2 pb-1.5 text-xs leading-relaxed text-muted-foreground">
                    {labels.disclosureList}
                </p>
                {invitations.length > 0 ? (
                    <>
                        <DropdownMenuLabel>{labels.invitations}</DropdownMenuLabel>
                        {invitations.map((invitation) => (
                            <DropdownMenuItem
                                key={`invitation:${invitation.id}`}
                                disabled={working}
                                onSelect={() => onJoinInvitation(invitation)}
                            >
                                <UserPlusIcon />
                                <span className="min-w-0 flex-1 truncate">{invitation.title}</span>
                                <span className="text-xs text-muted-foreground">{labels.join}</span>
                            </DropdownMenuItem>
                        ))}
                        <DropdownMenuSeparator />
                    </>
                ) : null}
                {sessions.length === 0 ? (
                    <DropdownMenuItem disabled>{labels.noRecentSessions}</DropdownMenuItem>
                ) : sessions.map((session) => (
                    <DropdownMenuItem
                        key={session.id}
                        disabled={working}
                        aria-current={activeSession?.id === session.id ? 'true' : undefined}
                        onSelect={() => onSelectSession(session)}
                    >
                        <span className="min-w-0 flex-1 truncate">{session.title}</span>
                        {activeSession?.id === session.id ? <CheckIcon className="size-4" /> : null}
                    </DropdownMenuItem>
                ))}
                <DropdownMenuSeparator />
                <DropdownMenuItem disabled={working} onSelect={onNewChat}>
                    <PlusIcon />
                    {labels.newChat}
                </DropdownMenuItem>
                <DropdownMenuItem
                    disabled={!activeSession?.ownedByCurrentUser}
                    onSelect={onBeginRename}
                >
                    <PencilSquareIcon />
                    {labels.rename}
                </DropdownMenuItem>
                {activeSession?.ownedByCurrentUser && canShare ? (
                    <DropdownMenuItem onSelect={onManageSharing}>
                        <UserGroupIcon />
                        {labels.manageSharing}
                    </DropdownMenuItem>
                ) : null}
                {activeSession && !activeSession.ownedByCurrentUser ? (
                    <DropdownMenuItem disabled={working} onSelect={onLeave}>
                        <ArrowRightStartOnRectangleIcon />
                        {labels.leave}
                    </DropdownMenuItem>
                ) : null}
                <DropdownMenuItem
                    variant="destructive"
                    disabled={!activeSession?.ownedByCurrentUser || working}
                    onSelect={onArchive}
                >
                    <ArchiveBoxIcon />
                    {labels.archive}
                </DropdownMenuItem>
            </DropdownMenuContent>
        </DropdownMenu>
    );
}

function ConversationSurface({
    sessions,
    invitations,
    activeSession,
    presence,
    canShare,
    messages,
    freshMessageIds,
    loadState,
    loadError,
    composer,
    implicitContext,
    selectionContext,
    unsupportedPageContext,
    pinnedContext,
    pageContextPinned,
    contextCorrected,
    scopePreview,
    attachments,
    fileAttachments,
    canAttachFiles,
    canRemoveFiles,
    contextOverflow,
    contentTooLong,
    working,
    turn,
    streamStore,
    streaming,
    cancelling,
    toolCalls,
    actionableToolCallIds,
    canRetryTurn,
    unavailable,
    starterPrompts,
    labels,
    closeButton,
    onSelectSession,
    onNewChat,
    onBeginRename,
    onManageSharing,
    onArchive,
    onJoinInvitation,
    onLeave,
    onRetry,
    onComposerChange,
    onRemoveAttachment,
    onTogglePagePin,
    onUnpinContext,
    onRemovePageContext,
    onRemoveSelectionContext,
    onResetContext,
    onAttachFiles,
    onRemoveFileAttachment,
    onSend,
    onCancelTurn,
    onRetryTurn,
    onToolAction,
    onOpenWorkspace,
    open,
    workspace,
}: ConversationSurfaceProps) {
    const transcript = useMemo(
        () => askConnexTranscript(messages, activeSession?.historySummarized === true),
        [activeSession?.historySummarized, messages],
    );
    const visibleMessages = transcript.messages;
    const toolCardAnchors = useMemo(
        () => anchorAskConnexToolCards(toolCalls, visibleMessages),
        [toolCalls, visibleMessages],
    );
    const toolCardInsertionIds = useMemo(
        () => new Set(toolCardAnchors.afterMessageId.keys()),
        [toolCardAnchors],
    );
    const groups = useMemo(
        () => groupAskConnexMessages(visibleMessages, toolCardInsertionIds),
        [toolCardInsertionIds, visibleMessages],
    );
    const fileInputRef = useRef<HTMLInputElement>(null);
    const [recordPickerRequest, setRecordPickerRequest] = useState(0);
    const fileOperationPending = hasPendingAskConnexFileOperation(fileAttachments);
    const busy = working || fileOperationPending;
    const suggestions = useMemo(
        () => latestAskConnexSuggestions(messages, busy),
        [busy, messages],
    );
    const latestMessageId = visibleMessages.at(-1)?.id ?? null;
    const historySummarized = transcript.historySummarized;
    const canSend = composer.trim().length > 0
        && loadState === 'ready'
        && !contextOverflow
        && !contentTooLong
        && !fileOperationPending
        && !busy
        && unavailable === null;
    const contextGroupRef = useRef<HTMLDivElement>(null);
    const [confirmedScopeKey, setConfirmedScopeKey] = useState<string | null>(null);
    const [pendingScope, setPendingScope] = useState<{ key: string; content?: string } | null>(null);
    const scopeKey = askConnexScopePreviewKey(scopePreview);
    const asking = pendingScope !== null && pendingScope.key === scopeKey;

    const requestSend = (content?: string) => {
        if (!canSend) return;
        if (scopeKey !== null && askConnexScopeNeedsConfirmation(scopeKey, confirmedScopeKey)) {
            setPendingScope({ key: scopeKey, content });
            return;
        }
        setPendingScope(null);
        onSend(content);
    };

    return (
        <div className={cn(
            'flex h-full min-h-0 flex-col text-popover-foreground',
            workspace ? 'bg-background' : 'bg-popover',
        )}>
            <header className="flex shrink-0 items-center gap-1 border-b border-border px-3 py-2">
                {workspace ? (
                    <h1 className="min-w-0 flex-1 truncate px-2 text-sm font-medium text-foreground">
                        {activeSession?.title ?? labels.newChat}
                    </h1>
                ) : (
                    <p className="min-w-0 flex-1 truncate px-2 text-sm font-medium text-foreground">
                        {activeSession?.title ?? labels.newChat}
                    </p>
                )}
                <SessionMenu
                    sessions={sessions}
                    invitations={invitations}
                    activeSession={activeSession}
                    canShare={canShare}
                    working={busy}
                    labels={labels}
                    onSelectSession={onSelectSession}
                    onNewChat={onNewChat}
                    onBeginRename={onBeginRename}
                    onArchive={onArchive}
                    onJoinInvitation={onJoinInvitation}
                    onManageSharing={onManageSharing}
                    onLeave={onLeave}
                />
                {!workspace ? (
                    <IconButton
                        type="button"
                        variant="ghost"
                        size="icon-toolbar"
                        label={labels.openWorkspace}
                        onClick={onOpenWorkspace}
                    >
                        <ArrowsPointingOutIcon className="size-4" />
                    </IconButton>
                ) : null}
                {closeButton}
            </header>

            {activeSession?.visibility === 'shared' && presence ? (
                <PresenceStrip presence={presence} labels={labels} />
            ) : null}

            <MessageScrollerProvider
                key={activeSession?.id ?? 'new'}
                autoScroll
                defaultScrollPosition="last-anchor"
                scrollPreviousItemPeek={48}
            >
                <MessageScroller className="min-h-0 flex-1">
                    <MessageScrollerViewport aria-label={labels.messages}>
                        <MessageScrollerContent
                            aria-busy={busy}
                            className={workspace ? 'mx-auto w-full max-w-4xl' : undefined}
                        >
                            {loadState === 'loading' ? (
                                <MessageScrollerItem messageId="loading">
                                    <TranscriptSkeleton />
                                </MessageScrollerItem>
                            ) : null}
                            {loadState === 'error' && loadError ? (
                                <MessageScrollerItem messageId="load-error">
                                    <SectionBoundary resetKey={activeSession?.id ?? 'new'}>
                                        <div className="[&>div]:min-h-0 [&>div]:px-4 [&>div]:py-12">
                                            <ErrorState error={loadError} retry={onRetry} showBack={false} />
                                        </div>
                                    </SectionBoundary>
                                </MessageScrollerItem>
                            ) : null}
                            {loadState === 'forbidden' ? (
                                <MessageScrollerItem messageId="forbidden" className="p-4">
                                    <AccessDenied
                                        variant="inline"
                                        title={unavailable?.title}
                                        body={unavailable?.body ?? ''}
                                    />
                                </MessageScrollerItem>
                            ) : null}
                            {loadState === 'ready' && historySummarized ? (
                                <MessageScrollerItem messageId="history-summary" className="px-4 pt-5">
                                    <HistorySummaryMarker label={labels.historySummarized} />
                                </MessageScrollerItem>
                            ) : null}
                            {loadState === 'ready' && visibleMessages.length === 0 && toolCalls.length === 0 ? (
                                <MessageScrollerItem messageId="empty">
                                    <EmptyState
                                        icon={SparklesIcon}
                                        title={labels.emptyTitle}
                                        body={labels.emptyBody}
                                        tone="brand"
                                        className="border-0 bg-transparent px-4 py-12"
                                        action={(
                                            <div className="flex w-full max-w-sm flex-col gap-1.5">
                                                {starterPrompts.map((prompt) => (
                                                    <Button
                                                        key={prompt}
                                                        type="button"
                                                        variant="ghost"
                                                        className="h-auto justify-start whitespace-normal bg-muted/60 py-2 text-left"
                                                        onClick={() => onComposerChange(prompt)}
                                                    >
                                                        {prompt}
                                                    </Button>
                                                ))}
                                                <p className="pt-3 text-xs leading-relaxed text-muted-foreground">
                                                    {labels.disclosureCreation}
                                                </p>
                                            </div>
                                        )}
                                    />
                                </MessageScrollerItem>
                            ) : null}
                            {loadState === 'ready' && toolCardAnchors.beforeMessages.length > 0 ? (
                                <MessageScrollerItem
                                    messageId="tool-calls-before-transcript"
                                    className="px-4 pt-5 last:pb-5"
                                >
                                    <div className="pl-10">
                                        <ToolCallCards
                                            cards={toolCardAnchors.beforeMessages}
                                            labels={labels.toolCard}
                                            actionsDisabled={activeSession === null}
                                            actionableToolCallIds={actionableToolCallIds}
                                            onAction={onToolAction}
                                        />
                                    </div>
                                </MessageScrollerItem>
                            ) : null}
                            {loadState === 'ready' && visibleMessages.length > 0 ? (
                                <>
                                    {groups.map((group) => {
                                        const first = group.messages[0];
                                        const last = group.messages.at(-1);
                                        if (!first || !last) return null;
                                        const anchoredCards = toolCardAnchors.afterMessageId.get(last.id) ?? [];
                                        const groupKey = `${first.sessionId}:${first.seq}:${last.seq}`;
                                        return (
                                            <Fragment key={groupKey}>
                                                <MessageScrollerItem
                                                    messageId={groupKey}
                                                    scrollAnchor={group.authorKind === 'user'}
                                                    className="px-4 pt-5 last:pb-5"
                                                >
                                                    <MessageGroup>
                                                        {group.messages.map((message, index) => (
                                                            <TranscriptMessage
                                                                key={message.id}
                                                                message={message}
                                                                fresh={freshMessageIds.has(message.id)}
                                                                firstInGroup={index === 0}
                                                                lastInGroup={index === group.messages.length - 1}
                                                                suggestions={message.id === latestMessageId ? suggestions : []}
                                                                toolCalls={toolCardAnchors.byMessageId.get(message.id) ?? []}
                                                                actionableToolCallIds={actionableToolCallIds}
                                                                labels={labels}
                                                                onSend={requestSend}
                                                                onToolAction={onToolAction}
                                                            />
                                                        ))}
                                                    </MessageGroup>
                                                </MessageScrollerItem>
                                                {anchoredCards.length > 0 ? (
                                                    <MessageScrollerItem
                                                        messageId={`tool-message:${last.id}`}
                                                        className="px-4 pt-5 last:pb-5"
                                                    >
                                                        <div className="pl-10">
                                                            <ToolCallCards
                                                                cards={anchoredCards}
                                                                labels={labels.toolCard}
                                                                actionsDisabled={activeSession === null}
                                                                actionableToolCallIds={actionableToolCallIds}
                                                                onAction={onToolAction}
                                                            />
                                                        </div>
                                                    </MessageScrollerItem>
                                                ) : null}
                                            </Fragment>
                                        );
                                    })}
                                </>
                            ) : null}
                            {loadState === 'ready' ? (
                                <StreamingTail store={streamStore} turn={turn} labels={labels} />
                            ) : null}
                            {turn.phase !== 'idle' ? (
                                <MessageScrollerItem messageId={`turn:${turn.turnId ?? turn.phase}`}>
                                    <TurnActivity
                                        turn={turn}
                                        streaming={streaming}
                                        cancelling={cancelling}
                                        canRetry={canRetryTurn}
                                        labels={labels}
                                        onCancel={onCancelTurn}
                                        onRetry={onRetryTurn}
                                    />
                                </MessageScrollerItem>
                            ) : null}
                        </MessageScrollerContent>
                    </MessageScrollerViewport>
                    <MessageScrollerButton aria-label={labels.jumpToLatest}>
                        <ArrowDownIcon className="size-4" />
                    </MessageScrollerButton>
                </MessageScroller>
            </MessageScrollerProvider>

            {unavailable ? (
                loadState !== 'forbidden' ? (
                    <div role="status" className="shrink-0 border-t border-border px-4 py-3 text-xs leading-relaxed text-muted-foreground">
                        <span className="font-medium text-foreground">{unavailable.title}</span>{' '}
                        {unavailable.body}
                    </div>
                ) : null
            ) : (
                <form
                    className="shrink-0 border-t border-border p-3"
                    onSubmit={(event) => {
                        event.preventDefault();
                        requestSend();
                    }}
                >
                    <AskConnexContextStrip
                        groupRef={contextGroupRef}
                        implicitContext={implicitContext}
                        pinnedContext={pinnedContext}
                        pageContextPinned={pageContextPinned}
                        selectionContext={selectionContext}
                        unsupportedPageContext={unsupportedPageContext}
                        attachments={attachments}
                        fileAttachments={fileAttachments}
                        canRemoveFiles={canRemoveFiles}
                        fileOperationPending={fileOperationPending}
                        overflow={contextOverflow}
                        corrected={contextCorrected}
                        labels={labels}
                        onRemove={onRemoveAttachment}
                        onRemoveFile={onRemoveFileAttachment}
                        onTogglePagePin={onTogglePagePin}
                        onUnpin={onUnpinContext}
                        onRemovePage={onRemovePageContext}
                        onRemoveSelection={onRemoveSelectionContext}
                        onReset={onResetContext}
                    />
                    {asking && scopePreview !== null ? (
                        <AskConnexScopeNotice
                            preview={scopePreview}
                            labels={labels}
                            onConfirm={() => {
                                if (scopeKey === null) return;
                                const content = pendingScope?.content;
                                setConfirmedScopeKey(scopeKey);
                                setPendingScope(null);
                                onSend(content);
                            }}
                            onEdit={() => {
                                setPendingScope(null);
                                contextGroupRef.current?.focus();
                            }}
                        />
                    ) : null}
                    {fileAttachments.some((attachment) => attachment.kind === 'image') ? (
                        <p className="mb-2 text-xs leading-relaxed text-muted-foreground">
                            {labels.imageDisclosure}
                        </p>
                    ) : null}
                    <div data-base-ui-swipe-ignore className="rounded-2xl border border-input bg-background p-2 focus-within:ring-2 focus-within:ring-ring/50">
                        <MentionEditor
                            value={composer}
                            onChange={onComposerChange}
                            onSubmit={() => requestSend()}
                            placeholder={labels.composerPlaceholder}
                            ariaLabel={labels.composerAria}
                            mentionTypes={ASK_CONNEX_MENTION_TYPES}
                            recordPickerRequest={recordPickerRequest}
                            className="min-h-16 max-h-36 overflow-y-auto px-1 py-1 text-sm leading-5"
                        />
                        <div className="mt-2 flex items-end gap-2">
                            {open ? (
                                <input
                                    ref={fileInputRef}
                                    type="file"
                                    multiple
                                    accept=".txt,.md,.markdown,.csv,.json,.jpg,.jpeg,.png,.webp,text/plain,text/markdown,text/csv,application/json,image/jpeg,image/png,image/webp"
                                    className="sr-only"
                                    tabIndex={-1}
                                    aria-label={labels.attachFile}
                                    onChange={(event) => {
                                        const files = Array.from(event.target.files ?? []);
                                        event.target.value = '';
                                        if (files.length > 0) onAttachFiles(files);
                                    }}
                                />
                            ) : null}
                            <DropdownMenu>
                                <DropdownMenuTrigger asChild>
                                    <IconButton
                                        type="button"
                                        variant="ghost"
                                        size="icon-toolbar"
                                        label={labels.addContext}
                                        disabled={busy || loadState !== 'ready' || unavailable !== null}
                                    >
                                        <PlusIcon className="size-4" />
                                    </IconButton>
                                </DropdownMenuTrigger>
                                <DropdownMenuContent align="start" side="top" className="w-52">
                                    <DropdownMenuItem
                                        disabled={!canAttachFiles}
                                        onSelect={() => fileInputRef.current?.click()}
                                    >
                                        <PaperClipIcon />
                                        {labels.attachFile}
                                    </DropdownMenuItem>
                                    <DropdownMenuItem onSelect={() => setRecordPickerRequest((value) => value + 1)}>
                                        <LinkIcon />
                                        {labels.addRecordContext}
                                    </DropdownMenuItem>
                                </DropdownMenuContent>
                            </DropdownMenu>
                            <div className="min-w-0 text-xs text-muted-foreground">
                                {contentTooLong ? (
                                    <p role="alert" className="text-destructive">{labels.tooLong}</p>
                                ) : (
                                    <p>{labels.composerHint}</p>
                                )}
                            </div>
                            <IconButton
                                className="ml-auto"
                                type="submit"
                                size="icon-toolbar"
                                label={labels.send}
                                disabled={!canSend}
                            >
                                <ArrowUpIcon className="size-4" />
                            </IconButton>
                        </div>
                    </div>
                </form>
            )}
        </div>
    );
}

/**
 * One destination in the workspace session rail. Invitations and chats are the same navigation
 * item — both land the user in a session — so they share one row rather than one row and a
 * look-alike, and both declare the current-page state the rail's `<nav>` implies.
 */
function SessionRailRow({
    title,
    current,
    disabled,
    leading,
    trailing,
    onSelect,
}: {
    title: string;
    current: boolean;
    disabled: boolean;
    leading?: ReactNode;
    trailing?: ReactNode;
    onSelect: () => void;
}) {
    return (
        <button
            type="button"
            disabled={disabled}
            aria-current={current ? 'page' : undefined}
            onClick={onSelect}
            className={cn(
                'flex min-h-10 w-full items-center gap-2 rounded-lg px-3 py-2 text-left text-sm outline-none hover:bg-muted focus-visible:ring-2 focus-visible:ring-ring disabled:opacity-50',
                current && 'bg-muted font-medium text-foreground',
            )}
        >
            {leading}
            <span className="min-w-0 flex-1 truncate">{title}</span>
            {trailing}
        </button>
    );
}

function WorkspaceSessionRail({
    sessions,
    invitations,
    activeSession,
    working,
    labels,
    onSelectSession,
    onJoinInvitation,
    onNewChat,
}: {
    sessions: AiChatSession[];
    invitations: AiChatSession[];
    activeSession: AiChatSession | null;
    working: boolean;
    labels: AskConnexDrawerLabels;
    onSelectSession: (session: AiChatSession) => void;
    onJoinInvitation: (session: AiChatSession) => void;
    onNewChat: () => void;
}) {
    const [query, setQuery] = useState('');
    const normalized = query.trim().toLocaleLowerCase();
    const filtered = normalized.length === 0
        ? sessions
        : sessions.filter((session) => session.title.toLocaleLowerCase().includes(normalized));
    return (
        <aside className="hidden min-h-0 w-72 shrink-0 flex-col border-r border-border bg-muted/30 md:flex">
            <div className="flex items-center gap-2 border-b border-border px-4 py-3">
                <div className="min-w-0 flex-1">
                    <p className="text-sm font-semibold text-foreground">{labels.title}</p>
                    <p className="truncate text-xs text-muted-foreground">{labels.recentSessions}</p>
                </div>
                <IconButton
                    type="button"
                    variant="ghost"
                    size="icon-toolbar"
                    label={labels.newChat}
                    disabled={working}
                    onClick={onNewChat}
                >
                    <PlusIcon className="size-4" />
                </IconButton>
            </div>
            <div className="p-3">
                <div className="relative">
                    <MagnifyingGlassIcon className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground" />
                    <Input
                        value={query}
                        onChange={(event) => setQuery(event.target.value)}
                        placeholder={labels.searchSessions}
                        aria-label={labels.searchSessions}
                        className="pl-9"
                    />
                </div>
            </div>
            <nav aria-label={labels.recentSessions} className="min-h-0 flex-1 overflow-y-auto px-2 pb-3">
                {invitations.map((invitation) => (
                    <SessionRailRow
                        key={`invitation:${invitation.id}`}
                        title={invitation.title}
                        current={false}
                        disabled={working}
                        leading={<UserPlusIcon className="size-4 shrink-0 text-muted-foreground" />}
                        trailing={<span className="text-xs text-muted-foreground">{labels.join}</span>}
                        onSelect={() => onJoinInvitation(invitation)}
                    />
                ))}
                {filtered.length === 0 ? (
                    <p className="px-3 py-8 text-center text-xs text-muted-foreground">
                        {labels.noMatchingSessions}
                    </p>
                ) : filtered.map((session) => (
                    <SessionRailRow
                        key={session.id}
                        title={session.title}
                        current={activeSession?.id === session.id}
                        disabled={working}
                        trailing={session.visibility === 'shared'
                            ? <UserGroupIcon className="size-4 shrink-0 text-muted-foreground" />
                            : null}
                        onSelect={() => onSelectSession(session)}
                    />
                ))}
            </nav>
            <p className="border-t border-border px-4 py-3 text-xs leading-relaxed text-muted-foreground">
                {labels.disclosureList}
            </p>
        </aside>
    );
}

/** Responsive Ask Connex session, transcript, context, and composer surface. */
export default function AskConnexDrawer(props: AskConnexDrawerProps) {
    const {
        open,
        instantOpen,
        isMobile,
        showTab,
        workspace,
        desktopRoot,
        workspaceRoot,
        activeSession,
        labels,
        onOpenChange,
        onOpenChangeComplete,
        onKeyboardClose,
        onRename,
    } = props;
    const reduceMotion = useReducedMotion() ?? false;
    const desktopTriggerRef = useRef<HTMLButtonElement>(null);
    const [renameOpen, setRenameOpen] = useState(false);
    const [renameValue, setRenameValue] = useState('');
    const [renaming, setRenaming] = useState(false);
    const [shareOpen, setShareOpen] = useState(false);
    const [sharing, setSharing] = useState(false);
    const [selectedMemberId, setSelectedMemberId] = useState('');
    const participantIds = new Set(props.participants.map((participant) => participant.userId));
    const availableMembers = props.members.filter((member) => (
        member.id !== activeSession?.createdByUserId && !participantIds.has(member.id)
    ));

    useEffect(() => {
        if (isMobile || !open) return;
        const handleKeyDown = (event: KeyboardEvent) => {
            if (event.key !== 'Escape' || event.defaultPrevented) return;
            const activeElement = document.activeElement;
            if (activeElement instanceof HTMLElement
                && activeElement.closest('[data-slot="dropdown-menu-content"], [data-slot="dialog-content"]')) return;
            onKeyboardClose();
            desktopTriggerRef.current?.focus();
        };
        document.addEventListener('keydown', handleKeyDown);
        return () => document.removeEventListener('keydown', handleKeyDown);
    }, [isMobile, onKeyboardClose, open]);

    const closeDesktopPanel = useCallback(() => {
        onOpenChange(false);
        desktopTriggerRef.current?.focus();
    }, [onOpenChange]);

    const beginRename = () => {
        if (!activeSession) return;
        setRenameValue(activeSession.title);
        setRenameOpen(true);
    };

    const saveRename = async () => {
        const title = renameValue.trim();
        if (!title || renaming) return;
        setRenaming(true);
        const saved = await onRename(title);
        setRenaming(false);
        if (saved) setRenameOpen(false);
    };

    const enableSharing = async () => {
        if (sharing) return;
        setSharing(true);
        await props.onShare(true);
        setSharing(false);
    };

    const disableSharing = async () => {
        if (sharing) return;
        setSharing(true);
        const saved = await props.onShare(false);
        setSharing(false);
        if (saved) setShareOpen(false);
    };

    const inviteSelectedMember = async () => {
        const memberId = Number(selectedMemberId);
        if (!Number.isSafeInteger(memberId) || memberId <= 0 || sharing) return;
        setSharing(true);
        const invited = await props.onInvite(memberId);
        setSharing(false);
        if (invited) setSelectedMemberId('');
    };

    const surfaceProps: Omit<ConversationSurfaceProps, 'open'> = {
        sessions: props.sessions,
        invitations: props.invitations,
        activeSession: props.activeSession,
        participants: props.participants,
        presence: props.presence,
        members: props.members,
        canShare: props.canShare,
        messages: props.messages,
        freshMessageIds: props.freshMessageIds,
        loadState: props.loadState,
        loadError: props.loadError,
        composer: props.composer,
        implicitContext: props.implicitContext,
        selectionContext: props.selectionContext,
        unsupportedPageContext: props.unsupportedPageContext,
        pinnedContext: props.pinnedContext,
        pageContextPinned: props.pageContextPinned,
        contextCorrected: props.contextCorrected,
        scopePreview: props.scopePreview,
        attachments: props.attachments,
        fileAttachments: props.fileAttachments,
        canAttachFiles: props.canAttachFiles,
        canRemoveFiles: props.canRemoveFiles,
        contextOverflow: props.contextOverflow,
        contentTooLong: props.contentTooLong,
        working: props.working,
        turn: props.turn,
        streamStore: props.streamStore,
        streaming: props.streaming,
        cancelling: props.cancelling,
        toolCalls: props.toolCalls,
        actionableToolCallIds: props.actionableToolCallIds,
        canRetryTurn: props.canRetryTurn,
        unavailable: props.unavailable,
        starterPrompts: props.starterPrompts,
        labels: props.labels,
        workspace: props.workspace,
        closeButton: null,
        onSelectSession: props.onSelectSession,
        onNewChat: props.onNewChat,
        onBeginRename: beginRename,
        onManageSharing: () => setShareOpen(true),
        onArchive: props.onArchive,
        onJoinInvitation: props.onJoinInvitation,
        onShare: props.onShare,
        onInvite: props.onInvite,
        onLeave: props.onLeave,
        onRemoveParticipant: props.onRemoveParticipant,
        onRetry: props.onRetry,
        onComposerChange: props.onComposerChange,
        onRemoveAttachment: props.onRemoveAttachment,
        onTogglePagePin: props.onTogglePagePin,
        onUnpinContext: props.onUnpinContext,
        onRemovePageContext: props.onRemovePageContext,
        onRemoveSelectionContext: props.onRemoveSelectionContext,
        onResetContext: props.onResetContext,
        onAttachFiles: props.onAttachFiles,
        onRemoveFileAttachment: props.onRemoveFileAttachment,
        onSend: props.onSend,
        onCancelTurn: props.onCancelTurn,
        onRetryTurn: props.onRetryTurn,
        onToolAction: props.onToolAction,
        onOpenWorkspace: props.onOpenWorkspace,
        onCloseWorkspace: props.onCloseWorkspace,
    };

    const desktopPanel = !workspace && !isMobile && desktopRoot ? createPortal(
        <>
            {showTab ? (
                <AskConnexTab
                    buttonRef={desktopTriggerRef}
                    label={labels.title}
                    closeLabel={labels.close}
                    open={open}
                    working={props.working}
                    onOpen={() => onOpenChange(true)}
                    onClose={closeDesktopPanel}
                />
            ) : null}
            <motion.aside
                id="ask-connex-desktop-panel"
                aria-label={labels.title}
                aria-hidden={!open}
                inert={!open}
                initial={false}
                animate={{ transform: open ? 'translateX(0%)' : 'translateX(100%)' }}
                transition={instantOpen || reduceMotion ? instant : springSmooth}
                onAnimationComplete={() => onOpenChangeComplete(open)}
                className={cn(
                    'absolute inset-y-0 right-0 w-96 border-l border-border bg-popover',
                    open ? 'pointer-events-auto' : 'pointer-events-none',
                )}
            >
                <ConversationSurface
                    {...surfaceProps}
                    open={open}
                    closeButton={(
                        <IconButton
                            type="button"
                            variant="ghost"
                            size="icon-toolbar"
                            label={labels.close}
                            onClick={closeDesktopPanel}
                        >
                            <XMarkIcon className="size-4" />
                        </IconButton>
                    )}
                />
            </motion.aside>
        </>,
        desktopRoot,
    ) : null;

    const workspacePanel = workspace && workspaceRoot ? createPortal(
        <div className="flex h-full min-h-0 bg-background text-foreground">
            <WorkspaceSessionRail
                sessions={props.sessions}
                invitations={props.invitations}
                activeSession={props.activeSession}
                working={props.working}
                labels={labels}
                onSelectSession={props.onSelectSession}
                onJoinInvitation={props.onJoinInvitation}
                onNewChat={props.onNewChat}
            />
            <div className="min-w-0 flex-1">
                <ConversationSurface
                    {...surfaceProps}
                    open
                    workspace
                    closeButton={(
                        <IconButton
                            type="button"
                            variant="ghost"
                            size="icon-toolbar"
                            label={labels.closeWorkspace}
                            onClick={props.onCloseWorkspace}
                        >
                            <ArrowLeftIcon className="size-4" />
                        </IconButton>
                    )}
                />
            </div>
        </div>,
        workspaceRoot,
    ) : null;

    return (
        <>
            {desktopPanel}
            {workspacePanel}
            {isMobile && !workspace ? (
                <Drawer
                    open={open}
                    onOpenChange={onOpenChange}
                    onOpenChangeComplete={onOpenChangeComplete}
                    modal
                    disablePointerDismissal
                    swipeDirection="down"
                    showSwipeHandle
                    motionClassName={instantOpen
                        ? 'duration-0'
                        : reduceMotion
                          ? 'transition-opacity duration-150 data-starting-style:opacity-0 data-ending-style:opacity-0 motion-reduce:transition-opacity'
                          : undefined}
                >
                    <DrawerContent
                        showCloseButton={false}
                        aria-describedby={undefined}
                        className="h-[calc(100dvh-1rem)] max-h-[calc(100dvh-1rem)] w-full gap-0 rounded-t-2xl p-0"
                    >
                        <DrawerTitle className="sr-only">{labels.title}</DrawerTitle>
                        <DrawerDescription className="sr-only">{labels.title}</DrawerDescription>
                        <ConversationSurface
                            {...surfaceProps}
                            open
                            closeButton={(
                                <IconButton
                                    type="button"
                                    variant="ghost"
                                    size="icon-toolbar"
                                    label={labels.close}
                                    onClick={() => onOpenChange(false)}
                                >
                                    <XMarkIcon className="size-4" />
                                </IconButton>
                            )}
                        />
                    </DrawerContent>
                </Drawer>
            ) : null}

            <Dialog open={renameOpen} onOpenChange={(next) => !renaming && setRenameOpen(next)}>
                <DialogContent size="sm">
                    <DialogHeader>
                        <DialogTitle>{labels.renameTitle}</DialogTitle>
                        <DialogDescription>{labels.renameDescription}</DialogDescription>
                    </DialogHeader>
                    <form
                        className="space-y-4"
                        onSubmit={(event) => {
                            event.preventDefault();
                            void saveRename();
                        }}
                    >
                        <label className="space-y-2 text-sm font-medium text-foreground">
                            <span>{labels.renameLabel}</span>
                            <Input
                                value={renameValue}
                                onChange={(event) => setRenameValue(event.target.value)}
                                maxLength={200}
                                autoFocus
                                disabled={renaming}
                            />
                        </label>
                        <DialogFooter>
                            <Button type="button" variant="ghost" onClick={() => setRenameOpen(false)} disabled={renaming}>
                                {labels.renameCancel}
                            </Button>
                            <Button type="submit" disabled={!renameValue.trim() || renaming}>
                                {renaming ? labels.renameSaving : labels.renameSave}
                            </Button>
                        </DialogFooter>
                    </form>
                </DialogContent>
            </Dialog>

            <Dialog open={shareOpen} onOpenChange={(next) => !sharing && setShareOpen(next)}>
                <DialogContent size="sm">
                    <DialogHeader>
                        <DialogTitle>{labels.shareTitle}</DialogTitle>
                        <DialogDescription>{labels.shareDescription}</DialogDescription>
                    </DialogHeader>
                    {activeSession?.visibility !== 'shared' ? (
                        <DialogFooter>
                            <Button type="button" variant="ghost" onClick={() => setShareOpen(false)} disabled={sharing}>
                                {labels.shareCancel}
                            </Button>
                            <Button type="button" onClick={() => void enableSharing()} disabled={sharing}>
                                {labels.shareConfirm}
                            </Button>
                        </DialogFooter>
                    ) : (
                        <div className="space-y-5">
                            <section className="space-y-2" aria-labelledby="ask-connex-participants-title">
                                <h3 id="ask-connex-participants-title" className="text-sm font-medium text-foreground">
                                    {labels.participants}
                                </h3>
                                <ul className="divide-y divide-border rounded-lg border border-border px-3">
                                    {props.participants.map((participant) => (
                                        <li key={participant.userId} className="flex min-w-0 items-center gap-3 py-2.5">
                                            <Avatar size="sm">
                                                <AvatarImage src={participant.profilePictureUrl ?? undefined} alt="" />
                                                <AvatarFallback>{participant.displayName.slice(0, 1).toUpperCase()}</AvatarFallback>
                                            </Avatar>
                                            <span className="min-w-0 flex-1 truncate text-sm text-foreground">
                                                {participant.displayName}
                                            </span>
                                            <span className="text-xs text-muted-foreground">
                                                {participant.status === 'invited' ? labels.invitePending : labels.shared}
                                            </span>
                                            {participant.role === 'participant' ? (
                                                <IconButton
                                                    type="button"
                                                    variant="ghost"
                                                    size="icon-toolbar"
                                                    label={labels.removeParticipant(participant.displayName)}
                                                    onClick={() => props.onRemoveParticipant(participant.userId)}
                                                    disabled={sharing}
                                                >
                                                    <XMarkIcon className="size-4" />
                                                </IconButton>
                                            ) : null}
                                        </li>
                                    ))}
                                </ul>
                            </section>
                            <div className="space-y-2">
                                <label htmlFor="ask-connex-invite-member" className="text-sm font-medium text-foreground">
                                    {labels.inviteMember}
                                </label>
                                <div className="flex gap-2">
                                    <Select value={selectedMemberId} onValueChange={setSelectedMemberId} disabled={sharing || availableMembers.length === 0}>
                                        <SelectTrigger id="ask-connex-invite-member" className="min-w-0 flex-1">
                                            <SelectValue placeholder={labels.inviteMember} />
                                        </SelectTrigger>
                                        <SelectContent>
                                            {availableMembers.map((member) => (
                                                <SelectItem key={member.id} value={String(member.id)}>
                                                    {member.displayName}
                                                </SelectItem>
                                            ))}
                                        </SelectContent>
                                    </Select>
                                    <Button
                                        type="button"
                                        onClick={() => void inviteSelectedMember()}
                                        disabled={!selectedMemberId || sharing}
                                    >
                                        {labels.invite}
                                    </Button>
                                </div>
                            </div>
                            <DialogFooter>
                                <Button type="button" variant="ghost" onClick={() => setShareOpen(false)} disabled={sharing}>
                                    {labels.shareCancel}
                                </Button>
                                <Button type="button" variant="destructive" onClick={() => void disableSharing()} disabled={sharing}>
                                    {labels.unshare}
                                </Button>
                            </DialogFooter>
                        </div>
                    )}
                </DialogContent>
            </Dialog>
        </>
    );
}
