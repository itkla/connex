'use client';

import { Fragment, useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import {
    ArchiveBoxIcon,
    ArrowRightStartOnRectangleIcon,
    ArrowDownIcon,
    ArrowUpIcon,
    CheckIcon,
    ChevronRightIcon,
    ClockIcon,
    EllipsisHorizontalIcon,
    ExclamationCircleIcon,
    LinkIcon,
    MapPinIcon,
    DocumentTextIcon,
    PaperClipIcon,
    PhotoIcon,
    PencilSquareIcon,
    PlusIcon,
    SparklesIcon,
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
import AskConnexTab from '@/app/components/ask-connex/AskConnexTab';
import AskConnexToolCard, {
    type AskConnexToolCardLabels,
} from '@/app/components/ask-connex/AskConnexToolCard';
import type {
    AskConnexAttachment,
    AskConnexFileAttachment,
    AskConnexToolAction,
    AskConnexToolCardState,
    AskConnexTurnState,
} from '@/app/lib/askConnex';
import {
    anchorAskConnexToolCards,
    askConnexCitationHref,
    askConnexCitations,
    askConnexTranscript,
    groupAskConnexMessages,
    hasPendingAskConnexFileOperation,
    latestAskConnexSuggestions,
} from '@/app/lib/askConnex';
import { easeOut, instant, springSmooth } from '@/app/lib/motion';
import { formatFileSize } from '@/app/lib/utils';
import type {
    AiChatCitation,
    AiChatMessage,
    AiChatParticipant,
    AiChatPresence,
    AiChatSession,
    WorkspaceMember,
} from '@/app/lib/types';
import { Avatar, AvatarFallback, AvatarGroup, AvatarImage } from '@/components/ui/avatar';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
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
    DrawerClose,
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

type AskConnexDrawerLabels = {
    assistantAuthor: string;
    addContext: string;
    addRecordContext: string;
    archive: string;
    attachFile: string;
    budgetExhausted: string;
    toolResultBudgetExhausted: string;
    citations: string;
    disclosureCreation: string;
    disclosureList: string;
    imageDisclosure: string;
    citationKind: (kind: AiChatCitation['kind']) => string;
    close: string;
    composerAria: string;
    composerHint: string;
    composerPlaceholder: string;
    context: string;
    contextLimit: string;
    emptyBody: string;
    emptyTitle: string;
    formerMember: string;
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
    moreOptions: string;
    participants: string;
    presence: string;
    recentSessions: string;
    removeContext: (label: string) => string;
    removeFile: (label: string) => string;
    removeParticipant: (name: string) => string;
    rename: string;
    renameCancel: string;
    renameDescription: string;
    renameLabel: string;
    renameSave: string;
    renameSaving: string;
    renameTitle: string;
    retry: string;
    send: string;
    shareCancel: string;
    shareConfirm: string;
    shareDescription: string;
    shared: string;
    shareTitle: string;
    suggestedFollowUps: string;
    thinking: string;
    title: string;
    tooLong: string;
    typing: (names: string) => string;
    unshare: string;
    turnAccepted: string;
    turnFailed: string;
    turnImageUnsupported: string;
    turnResolved: string;
    turnTimedOut: string;
    turnWorking: string;
    uploadProgress: (progress: number) => string;
    uploadRemoving: string;
    toolCard: AskConnexToolCardLabels;
};

type AskConnexDrawerProps = {
    open: boolean;
    instantOpen: boolean;
    isMobile: boolean;
    showTab: boolean;
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
    attachments: AskConnexAttachment[];
    fileAttachments: AskConnexFileAttachment[];
    canAttachFiles: boolean;
    canRemoveFiles: boolean;
    contextOverflow: boolean;
    contentTooLong: boolean;
    working: boolean;
    turn: AskConnexTurnState;
    toolCalls: AskConnexToolCardState[];
    actionableToolCallIds: ReadonlySet<number>;
    unavailable: UnavailableState;
    starterPrompts: string[];
    labels: AskConnexDrawerLabels;
    onOpenChange: (open: boolean) => void;
    onOpenChangeComplete: (open: boolean) => void;
    onKeyboardClose: () => void;
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
    onAttachFiles: (files: File[]) => void;
    onRemoveFileAttachment: (attachment: AskConnexFileAttachment) => void;
    onSend: (content?: string) => void;
    onToolAction: (toolCallId: number, action: AskConnexToolAction) => void;
};

type ConversationSurfaceProps = Omit<
    AskConnexDrawerProps,
    'instantOpen' | 'isMobile' | 'showTab' | 'onOpenChange' | 'onOpenChangeComplete' | 'onKeyboardClose' | 'onRename'
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
                        size="xs"
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

function MessageReasoning({ reasoning, label }: { reasoning: string | null | undefined; label: string }) {
    const content = reasoning?.trim();
    if (!content) return null;

    return (
        <details className="group text-xs text-muted-foreground">
            <summary className="flex min-h-8 cursor-pointer list-none items-center gap-1.5 rounded-md px-1 font-medium outline-none hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring [&::-webkit-details-marker]:hidden">
                <ChevronRightIcon className="size-3.5 shrink-0 group-open:rotate-90" />
                <span>{label}</span>
            </summary>
            <div className="ml-2 whitespace-pre-wrap break-words border-l border-border py-1 pl-4 leading-relaxed">
                {content}
            </div>
        </details>
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
            <MessageContent className="w-auto max-w-[85%] gap-1.5">
                {firstInGroup ? (
                    <MessageHeader className={user ? 'justify-end' : undefined}>
                        {author}
                    </MessageHeader>
                ) : null}
                {!user ? <MessageReasoning reasoning={message.reasoning} label={labels.thinking} /> : null}
                <motion.div
                    initial={animateEntrance ? (reduceMotion ? { opacity: 0 } : { opacity: 0, transform: 'translateY(0.375rem)' }) : false}
                    animate={{ opacity: 1, transform: 'translateY(0rem)' }}
                    transition={reduceMotion ? instant : { duration: 0.2, ease: easeOut }}
                    className={cn(
                        'whitespace-pre-wrap break-words rounded-2xl px-3.5 py-2.5 text-sm leading-relaxed',
                        user
                            ? 'bg-primary text-primary-foreground'
                            : 'bg-muted text-foreground',
                    )}
                >
                    {message.content}
                </motion.div>
                {!user ? <MessageCitations citations={message.citations} labels={labels} /> : null}
                {!user ? (
                    <ToolCallCards
                        cards={toolCalls}
                        labels={labels.toolCard}
                        actionsDisabled={false}
                        actionableToolCallIds={actionableToolCallIds}
                        onAction={onToolAction}
                    />
                ) : null}
                {!user ? (
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

function TurnActivity({ turn, labels }: { turn: AskConnexTurnState; labels: AskConnexDrawerLabels }) {
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
            <div role="status" className="flex items-center gap-2 px-4 py-2 text-xs text-destructive">
                <ExclamationCircleIcon className="size-3.5" />
                <span>{turn.reason === 'image_input_unsupported'
                    ? labels.turnImageUnsupported
                    : turn.reason === 'tool_result_budget_exhausted'
                        ? labels.toolResultBudgetExhausted
                        : turn.reason === 'budget_exhausted'
                            ? labels.budgetExhausted
                            : labels.turnFailed}</span>
            </div>
        );
    }
    if (turn.phase === 'timed_out') {
        return (
            <div role="status" className="flex items-center gap-2 px-4 py-2 text-xs text-muted-foreground">
                <ClockIcon className="size-3.5" />
                <span>{labels.turnTimedOut}</span>
            </div>
        );
    }
    return (
        <div role="status" className="flex items-center gap-2 px-4 py-2 text-xs text-muted-foreground">
            <SparklesIcon className="size-3.5" />
            <span>{turn.phase === 'accepted' ? labels.turnAccepted : labels.turnWorking}</span>
        </div>
    );
}

function ContextChips({
    implicitContext,
    attachments,
    fileAttachments,
    canRemoveFiles,
    fileOperationPending,
    overflow,
    labels,
    onRemove,
    onRemoveFile,
}: {
    implicitContext: AskConnexAttachment | null;
    attachments: AskConnexAttachment[];
    fileAttachments: AskConnexFileAttachment[];
    canRemoveFiles: boolean;
    fileOperationPending: boolean;
    overflow: boolean;
    labels: AskConnexDrawerLabels;
    onRemove: (attachment: AskConnexAttachment) => void;
    onRemoveFile: (attachment: AskConnexFileAttachment) => void;
}) {
    if (!implicitContext && attachments.length === 0 && fileAttachments.length === 0 && !overflow) return null;

    return (
        <div role="group" className="mb-2" aria-label={labels.context}>
            <div className="flex min-w-0 items-center gap-1.5 overflow-x-auto">
                {implicitContext ? (
                    <Badge variant="outline" className="max-w-44 shrink-0 text-muted-foreground">
                        <MapPinIcon />
                        <span className="truncate">{implicitContext.label}</span>
                    </Badge>
                ) : null}
                {attachments.map((attachment) => (
                    <Badge key={`${attachment.kind}:${attachment.id}`} variant="secondary" className="max-w-44 shrink-0 pr-1">
                        <span className="truncate">{attachment.label}</span>
                        <button
                            type="button"
                            aria-label={labels.removeContext(attachment.label)}
                            onClick={() => onRemove(attachment)}
                            className="rounded-full p-0.5 outline-none hover:bg-foreground/10 focus-visible:ring-2 focus-visible:ring-ring"
                        >
                            <XMarkIcon className="size-3" />
                        </button>
                    </Badge>
                ))}
                {fileAttachments.map((attachment) => {
                    const FileIcon = attachment.kind === 'image' ? PhotoIcon : DocumentTextIcon;
                    const detail = attachment.status === 'uploading'
                        ? labels.uploadProgress(attachment.progress)
                        : attachment.status === 'removing'
                            ? labels.uploadRemoving
                        : attachment.status === 'failed'
                            ? attachment.error
                            : formatFileSize(attachment.size);
                    return (
                        <Badge
                            key={attachment.clientId}
                            variant={attachment.status === 'failed' ? 'destructive' : 'outline'}
                            aria-invalid={attachment.status === 'failed' || undefined}
                            className="h-auto max-w-56 shrink-0 py-1 pr-1"
                        >
                            <FileIcon />
                            <span className="min-w-0">
                                <span className="block truncate">{attachment.fileName}</span>
                                <span
                                    role={attachment.status === 'failed'
                                        ? 'alert'
                                        : attachment.status === 'uploading' || attachment.status === 'removing'
                                            ? 'status'
                                            : undefined}
                                    className="block truncate text-[10px] font-normal opacity-70"
                                >
                                    {detail}
                                </span>
                            </span>
                            <button
                                type="button"
                                aria-label={labels.removeFile(attachment.fileName)}
                                disabled={!canRemoveFiles
                                    || fileOperationPending
                                    || attachment.status === 'uploading'
                                    || attachment.status === 'removing'}
                                onClick={() => onRemoveFile(attachment)}
                                className="rounded-full p-0.5 outline-none hover:bg-foreground/10 focus-visible:ring-2 focus-visible:ring-ring"
                            >
                                <XMarkIcon className="size-3" />
                            </button>
                        </Badge>
                    );
                })}
            </div>
            {overflow ? <p role="alert" className="mt-1.5 text-xs text-destructive">{labels.contextLimit}</p> : null}
        </div>
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
                <Button variant="ghost" size="icon-sm" aria-label={labels.moreOptions}>
                    <EllipsisHorizontalIcon className="size-4" />
                </Button>
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
    attachments,
    fileAttachments,
    canAttachFiles,
    canRemoveFiles,
    contextOverflow,
    contentTooLong,
    working,
    turn,
    toolCalls,
    actionableToolCallIds,
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
    onAttachFiles,
    onRemoveFileAttachment,
    onSend,
    onToolAction,
    open,
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

    return (
        <div className="flex h-full min-h-0 flex-col bg-popover text-popover-foreground">
            <header className="flex shrink-0 items-center gap-1 border-b border-border px-3 py-2">
                <p className="min-w-0 flex-1 truncate px-2 text-sm font-medium text-foreground">
                    {activeSession?.title ?? labels.newChat}
                </p>
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
                        <MessageScrollerContent aria-busy={busy}>
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
                                                                onSend={onSend}
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
                            {turn.phase !== 'idle' ? (
                                <MessageScrollerItem messageId={`turn:${turn.turnId ?? turn.phase}`}>
                                    <TurnActivity turn={turn} labels={labels} />
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
                        if (canSend) onSend();
                    }}
                >
                    <ContextChips
                        implicitContext={implicitContext}
                        attachments={attachments}
                        fileAttachments={fileAttachments}
                        canRemoveFiles={canRemoveFiles}
                        fileOperationPending={fileOperationPending}
                        overflow={contextOverflow}
                        labels={labels}
                        onRemove={onRemoveAttachment}
                        onRemoveFile={onRemoveFileAttachment}
                    />
                    {fileAttachments.some((attachment) => attachment.kind === 'image') ? (
                        <p className="mb-2 text-xs leading-relaxed text-muted-foreground">
                            {labels.imageDisclosure}
                        </p>
                    ) : null}
                    <div data-base-ui-swipe-ignore className="rounded-2xl border border-input bg-background p-2 focus-within:ring-2 focus-within:ring-ring/50">
                        <MentionEditor
                            value={composer}
                            onChange={onComposerChange}
                            onSubmit={() => {
                                if (canSend) onSend();
                            }}
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
                                    <Button
                                        type="button"
                                        variant="ghost"
                                        size="icon-sm"
                                        aria-label={labels.addContext}
                                        disabled={busy || loadState !== 'ready' || unavailable !== null}
                                    >
                                        <PlusIcon className="size-4" />
                                    </Button>
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
                            <Button className="ml-auto" type="submit" size="icon-sm" aria-label={labels.send} disabled={!canSend}>
                                <ArrowUpIcon className="size-4" />
                            </Button>
                        </div>
                    </div>
                </form>
            )}
        </div>
    );
}

/** Responsive Ask Connex session, transcript, context, and composer surface. */
export default function AskConnexDrawer(props: AskConnexDrawerProps) {
    const {
        open,
        instantOpen,
        isMobile,
        showTab,
        activeSession,
        labels,
        onOpenChange,
        onOpenChangeComplete,
        onKeyboardClose,
        onRename,
    } = props;
    const reduceMotion = useReducedMotion() ?? false;
    const desktopTriggerRef = useRef<HTMLButtonElement>(null);
    const [desktopRoot, setDesktopRoot] = useState<HTMLElement | null>(null);
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
        const frame = requestAnimationFrame(() => {
            setDesktopRoot(document.getElementById('ask-connex-desktop-root'));
        });
        return () => cancelAnimationFrame(frame);
    }, []);

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
        attachments: props.attachments,
        fileAttachments: props.fileAttachments,
        canAttachFiles: props.canAttachFiles,
        canRemoveFiles: props.canRemoveFiles,
        contextOverflow: props.contextOverflow,
        contentTooLong: props.contentTooLong,
        working: props.working,
        turn: props.turn,
        toolCalls: props.toolCalls,
        actionableToolCallIds: props.actionableToolCallIds,
        unavailable: props.unavailable,
        starterPrompts: props.starterPrompts,
        labels: props.labels,
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
        onAttachFiles: props.onAttachFiles,
        onRemoveFileAttachment: props.onRemoveFileAttachment,
        onSend: props.onSend,
        onToolAction: props.onToolAction,
    };

    const desktopPanel = !isMobile && desktopRoot ? createPortal(
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
                        <Button
                            type="button"
                            variant="ghost"
                            size="icon-sm"
                            aria-label={labels.close}
                            onClick={closeDesktopPanel}
                        >
                            <XMarkIcon className="size-4" />
                        </Button>
                    )}
                />
            </motion.aside>
        </>,
        desktopRoot,
    ) : null;

    return (
        <>
            {desktopPanel}
            {isMobile ? (
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
                                <DrawerClose render={<Button variant="ghost" size="icon-sm" aria-label={labels.close} />}>
                                    <XMarkIcon className="size-4" />
                                </DrawerClose>
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
                                                <Button
                                                    type="button"
                                                    variant="ghost"
                                                    size="icon-sm"
                                                    aria-label={labels.removeParticipant(participant.displayName)}
                                                    onClick={() => props.onRemoveParticipant(participant.userId)}
                                                    disabled={sharing}
                                                >
                                                    <XMarkIcon className="size-4" />
                                                </Button>
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
