'use client';

import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import {
    ArchiveBoxIcon,
    ArrowDownIcon,
    ArrowUpIcon,
    CheckIcon,
    ClockIcon,
    EllipsisHorizontalIcon,
    ExclamationCircleIcon,
    LinkIcon,
    MapPinIcon,
    PencilSquareIcon,
    PlusIcon,
    SparklesIcon,
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
import type { AskConnexAttachment, AskConnexTurnState } from '@/app/lib/askConnex';
import { askConnexCitationHref, askConnexCitations, groupAskConnexMessages } from '@/app/lib/askConnex';
import { easeOut, instant, springSmooth } from '@/app/lib/motion';
import type { AiChatCitation, AiChatMessage, AiChatSession } from '@/app/lib/types';
import { Avatar, AvatarFallback } from '@/components/ui/avatar';
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
} from '@/components/ui/message';
import {
    MessageScroller,
    MessageScrollerButton,
    MessageScrollerContent,
    MessageScrollerItem,
    MessageScrollerProvider,
    MessageScrollerViewport,
} from '@/components/ui/message-scroller';
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
    archive: string;
    citations: string;
    disclosureCreation: string;
    disclosureList: string;
    citationKind: (kind: AiChatCitation['kind']) => string;
    close: string;
    composerAria: string;
    composerHint: string;
    composerPlaceholder: string;
    context: string;
    contextLimit: string;
    emptyBody: string;
    emptyTitle: string;
    jumpToLatest: string;
    loadError: string;
    messages: string;
    newChat: string;
    noRecentSessions: string;
    moreOptions: string;
    recentSessions: string;
    removeContext: (label: string) => string;
    rename: string;
    renameCancel: string;
    renameDescription: string;
    renameLabel: string;
    renameSave: string;
    renameSaving: string;
    renameTitle: string;
    retry: string;
    send: string;
    title: string;
    tooLong: string;
    turnAccepted: string;
    turnFailed: string;
    turnResolved: string;
    turnTimedOut: string;
    turnWorking: string;
};

type AskConnexDrawerProps = {
    open: boolean;
    instantOpen: boolean;
    isMobile: boolean;
    showTab: boolean;
    sessions: AiChatSession[];
    activeSession: AiChatSession | null;
    messages: AiChatMessage[];
    freshMessageIds: ReadonlySet<number>;
    loadState: DrawerLoadState;
    loadError: Error | null;
    composer: string;
    implicitContext: AskConnexAttachment | null;
    attachments: AskConnexAttachment[];
    contextOverflow: boolean;
    contentTooLong: boolean;
    turn: AskConnexTurnState;
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
    onRetry: () => void;
    onComposerChange: (value: string) => void;
    onRemoveAttachment: (attachment: AskConnexAttachment) => void;
    onSend: () => void;
};

type ConversationSurfaceProps = Omit<
    AskConnexDrawerProps,
    'open' | 'instantOpen' | 'isMobile' | 'showTab' | 'onOpenChange' | 'onOpenChangeComplete' | 'onKeyboardClose' | 'onRename'
> & {
    closeButton: ReactNode;
    onBeginRename: () => void;
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

function SenderAvatar({ user }: { user: boolean }) {
    return (
        <MessageAvatar aria-hidden>
            <Avatar size="sm">
                <AvatarFallback className={user ? 'bg-primary text-primary-foreground' : undefined}>
                    {user ? <UserIcon className="size-3.5" /> : <SparklesIcon className="size-3.5" />}
                </AvatarFallback>
            </Avatar>
        </MessageAvatar>
    );
}

function TranscriptMessage({
    message,
    fresh,
    lastInGroup,
    labels,
}: {
    message: AiChatMessage;
    fresh: boolean;
    lastInGroup: boolean;
    labels: AskConnexDrawerLabels;
}) {
    const format = useFormatter();
    const reduceMotion = useReducedMotion() ?? false;
    const user = message.authorKind === 'user';
    const animateEntrance = fresh && !user;
    const createdAt = new Date(message.createdAt);
    const timestamp = Number.isNaN(createdAt.getTime())
        ? null
        : format.dateTime(createdAt, { hour: 'numeric', minute: '2-digit' });

    return (
        <Message align={user ? 'end' : 'start'}>
            {lastInGroup ? <SenderAvatar user={user} /> : <MessageAvatar aria-hidden className="bg-transparent" />}
            <MessageContent className="w-auto max-w-[85%] gap-1.5">
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
                {lastInGroup && timestamp ? (
                    <MessageFooter className="px-1 font-normal">
                        <time dateTime={message.createdAt}>{timestamp}</time>
                    </MessageFooter>
                ) : null}
            </MessageContent>
        </Message>
    );
}

function TurnActivity({ turn, labels }: { turn: AskConnexTurnState; labels: AskConnexDrawerLabels }) {
    if (turn.phase === 'idle') return null;
    if (turn.phase === 'resolved') {
        return (
            <div className="flex items-center gap-2 px-4 py-2 text-xs text-muted-foreground">
                <CheckIcon className="size-3.5 text-primary" />
                <span>{labels.turnResolved}</span>
            </div>
        );
    }
    if (turn.phase === 'failed') {
        return (
            <div role="status" className="flex items-center gap-2 px-4 py-2 text-xs text-destructive">
                <ExclamationCircleIcon className="size-3.5" />
                <span>{labels.turnFailed}</span>
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
    overflow,
    labels,
    onRemove,
}: {
    implicitContext: AskConnexAttachment | null;
    attachments: AskConnexAttachment[];
    overflow: boolean;
    labels: AskConnexDrawerLabels;
    onRemove: (attachment: AskConnexAttachment) => void;
}) {
    if (!implicitContext && attachments.length === 0 && !overflow) return null;

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
            </div>
            {overflow ? <p role="alert" className="mt-1.5 text-xs text-destructive">{labels.contextLimit}</p> : null}
        </div>
    );
}

function SessionMenu({
    sessions,
    activeSession,
    working,
    labels,
    onSelectSession,
    onNewChat,
    onBeginRename,
    onArchive,
}: {
    sessions: AiChatSession[];
    activeSession: AiChatSession | null;
    working: boolean;
    labels: AskConnexDrawerLabels;
    onSelectSession: (session: AiChatSession) => void;
    onNewChat: () => void;
    onBeginRename: () => void;
    onArchive: () => void;
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
                {sessions.length === 0 ? (
                    <DropdownMenuItem disabled>{labels.noRecentSessions}</DropdownMenuItem>
                ) : sessions.map((session) => (
                    <DropdownMenuItem
                        key={session.id}
                        disabled={working}
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
    activeSession,
    messages,
    freshMessageIds,
    loadState,
    loadError,
    composer,
    implicitContext,
    attachments,
    contextOverflow,
    contentTooLong,
    turn,
    unavailable,
    starterPrompts,
    labels,
    closeButton,
    onSelectSession,
    onNewChat,
    onBeginRename,
    onArchive,
    onRetry,
    onComposerChange,
    onRemoveAttachment,
    onSend,
}: ConversationSurfaceProps) {
    const groups = useMemo(() => groupAskConnexMessages(messages), [messages]);
    const working = turn.phase === 'accepted' || turn.phase === 'running';
    const canSend = composer.trim().length > 0
        && loadState === 'ready'
        && !contextOverflow
        && !contentTooLong
        && !working
        && unavailable === null;

    return (
        <div className="flex h-full min-h-0 flex-col bg-popover text-popover-foreground">
            <header className="flex shrink-0 items-center gap-1 border-b border-border px-3 py-2">
                <p className="min-w-0 flex-1 truncate px-2 text-sm font-medium text-foreground">
                    {activeSession?.title ?? labels.newChat}
                </p>
                <SessionMenu
                    sessions={sessions}
                    activeSession={activeSession}
                    working={working}
                    labels={labels}
                    onSelectSession={onSelectSession}
                    onNewChat={onNewChat}
                    onBeginRename={onBeginRename}
                    onArchive={onArchive}
                />
                {closeButton}
            </header>

            <MessageScrollerProvider
                key={activeSession?.id ?? 'new'}
                autoScroll
                defaultScrollPosition="last-anchor"
                scrollPreviousItemPeek={48}
            >
                <MessageScroller className="min-h-0 flex-1">
                    <MessageScrollerViewport aria-label={labels.messages}>
                        <MessageScrollerContent aria-busy={working}>
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
                            {loadState === 'ready' && messages.length === 0 ? (
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
                            {loadState === 'ready' && messages.length > 0 ? (
                                <>
                                    {groups.map((group) => {
                                        const first = group.messages[0];
                                        const last = group.messages.at(-1);
                                        if (!first || !last) return null;
                                        return (
                                            <MessageScrollerItem
                                                key={`${first.sessionId}:${first.seq}:${last.seq}`}
                                                messageId={`${first.sessionId}:${first.seq}:${last.seq}`}
                                                scrollAnchor={group.authorKind === 'user'}
                                                className="px-4 pt-5 last:pb-5"
                                            >
                                                <MessageGroup>
                                                    {group.messages.map((message, index) => (
                                                        <TranscriptMessage
                                                            key={message.id}
                                                            message={message}
                                                            fresh={freshMessageIds.has(message.id)}
                                                            lastInGroup={index === group.messages.length - 1}
                                                            labels={labels}
                                                        />
                                                    ))}
                                                </MessageGroup>
                                            </MessageScrollerItem>
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
                        overflow={contextOverflow}
                        labels={labels}
                        onRemove={onRemoveAttachment}
                    />
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
                            className="min-h-16 max-h-36 overflow-y-auto px-1 py-1 text-sm leading-5"
                        />
                        <div className="mt-2 flex items-end justify-between gap-3">
                            <div className="min-w-0 text-xs text-muted-foreground">
                                {contentTooLong ? (
                                    <p role="alert" className="text-destructive">{labels.tooLong}</p>
                                ) : (
                                    <p>{labels.composerHint}</p>
                                )}
                            </div>
                            <Button type="submit" size="icon-sm" aria-label={labels.send} disabled={!canSend}>
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

    const surfaceProps: ConversationSurfaceProps = {
        sessions: props.sessions,
        activeSession: props.activeSession,
        messages: props.messages,
        freshMessageIds: props.freshMessageIds,
        loadState: props.loadState,
        loadError: props.loadError,
        composer: props.composer,
        implicitContext: props.implicitContext,
        attachments: props.attachments,
        contextOverflow: props.contextOverflow,
        contentTooLong: props.contentTooLong,
        turn: props.turn,
        unavailable: props.unavailable,
        starterPrompts: props.starterPrompts,
        labels: props.labels,
        closeButton: null,
        onSelectSession: props.onSelectSession,
        onNewChat: props.onNewChat,
        onBeginRename: beginRename,
        onArchive: props.onArchive,
        onRetry: props.onRetry,
        onComposerChange: props.onComposerChange,
        onRemoveAttachment: props.onRemoveAttachment,
        onSend: props.onSend,
    };

    const desktopPanel = !isMobile && desktopRoot ? createPortal(
        <>
            {showTab ? (
                <AskConnexTab
                    buttonRef={desktopTriggerRef}
                    label={labels.title}
                    closeLabel={labels.close}
                    open={open}
                    working={props.turn.phase === 'accepted' || props.turn.phase === 'running'}
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
        </>
    );
}
