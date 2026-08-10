'use client';

import { useEffect, useRef, useState } from 'react';
import {
    ArchiveBoxIcon,
    CheckIcon,
    ChevronDownIcon,
    ClockIcon,
    EllipsisHorizontalIcon,
    MapPinIcon,
    PaperAirplaneIcon,
    PencilSquareIcon,
    PlusIcon,
    SparklesIcon,
    XMarkIcon,
} from '@heroicons/react/24/outline';
import { motion, useReducedMotion } from 'motion/react';

import AccessDenied from '@/app/components/AccessDenied';
import { EmptyState } from '@/app/components/EmptyState';
import ErrorState from '@/app/components/ErrorState';
import MentionEditor from '@/app/components/activity/notes/MentionEditor';
import type { AskConnexAttachment, AskConnexTurnState } from '@/app/lib/askConnex';
import { easeOut, instant } from '@/app/lib/motion';
import type { AiChatMessage, AiChatSession } from '@/app/lib/types';
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
    DrawerHeader,
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
import { ScrollArea } from '@/components/ui/scroll-area';
import { Skeleton } from '@/components/ui/skeleton';

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
    close: string;
    composerAria: string;
    composerHint: string;
    composerPlaceholder: string;
    context: string;
    contextLimit: string;
    contextNone: string;
    emptyBody: string;
    emptyTitle: string;
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
    onSelectSession: (session: AiChatSession) => void;
    onNewChat: () => void;
    onRename: (title: string) => Promise<boolean>;
    onArchive: () => void;
    onRetry: () => void;
    onComposerChange: (value: string) => void;
    onRemoveAttachment: (attachment: AskConnexAttachment) => void;
    onSend: () => void;
};

function TranscriptSkeleton() {
    return (
        <div className="space-y-6 px-4 py-6">
            <div className="ml-8 space-y-2 rounded-lg bg-muted p-4">
                <Skeleton className="h-3 w-4/5" />
                <Skeleton className="h-3 w-2/5" />
            </div>
            <div className="space-y-3 border-t border-border pt-6">
                <Skeleton className="h-3 w-full" />
                <Skeleton className="h-3 w-11/12" />
                <Skeleton className="h-3 w-3/4" />
            </div>
        </div>
    );
}

function TranscriptMessage({ message, fresh }: { message: AiChatMessage; fresh: boolean }) {
    const reduceMotion = useReducedMotion() ?? false;
    const user = message.authorKind === 'user';

    return (
        <motion.article
            initial={fresh ? (reduceMotion ? { opacity: 0 } : { opacity: 0, y: 6 }) : false}
            animate={{ opacity: 1, y: 0 }}
            transition={reduceMotion ? instant : { duration: 0.2, ease: easeOut }}
            className="border-t border-border py-6 first:border-t-0"
        >
            {user ? (
                <p className="ml-8 whitespace-pre-wrap break-words rounded-lg bg-muted px-4 py-3 text-sm leading-relaxed text-foreground">
                    {message.content}
                </p>
            ) : (
                <div className="whitespace-pre-wrap break-words text-sm leading-relaxed text-foreground">
                    {message.content}
                </div>
            )}
        </motion.article>
    );
}

function TurnActivity({ turn, labels }: { turn: AskConnexTurnState; labels: AskConnexDrawerLabels }) {
    const reduceMotion = useReducedMotion() ?? false;
    if (turn.phase === 'idle') return null;
    if (turn.phase === 'resolved') {
        return (
            <div className="flex items-center gap-2 border-t border-border px-4 py-2 text-xs text-muted-foreground">
                <CheckIcon className="size-3.5 text-primary" />
                <span>{labels.turnResolved}</span>
            </div>
        );
    }
    if (turn.phase === 'failed') {
        return (
            <div role="status" className="flex items-center gap-2 border-t border-border px-4 py-2 text-xs text-destructive">
                <span className="size-2 rounded-full bg-destructive" />
                <span>{labels.turnFailed}</span>
            </div>
        );
    }
    if (turn.phase === 'timed_out') {
        return (
            <div role="status" className="flex items-center gap-2 border-t border-border px-4 py-2 text-xs text-muted-foreground">
                <ClockIcon className="size-3.5" />
                <span>{labels.turnTimedOut}</span>
            </div>
        );
    }
    return (
        <div role="status" className="flex items-center gap-2 border-t border-border px-4 py-2 text-xs text-muted-foreground">
            <motion.span
                className="size-2 rounded-full bg-primary"
                animate={reduceMotion ? { opacity: 1 } : { opacity: [0.4, 1, 0.4] }}
                transition={reduceMotion ? instant : { duration: 1.2, ease: 'easeInOut', repeat: Infinity }}
            />
            <span>{turn.phase === 'accepted' ? labels.turnAccepted : labels.turnWorking}</span>
        </div>
    );
}

function ContextStrip({
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
    return (
        <div className="shrink-0 border-b border-border px-4 py-3" aria-label={labels.context}>
            <div className="flex min-w-0 items-center gap-2 overflow-x-auto">
                <span className="shrink-0 text-xs font-medium text-muted-foreground">{labels.context}</span>
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
                {!implicitContext && attachments.length === 0 ? (
                    <span className="truncate text-xs text-muted-foreground">{labels.contextNone}</span>
                ) : null}
            </div>
            {overflow ? <p role="alert" className="mt-2 text-xs text-destructive">{labels.contextLimit}</p> : null}
        </div>
    );
}

/** Responsive Ask Connex session, transcript, context, and composer surface. */
export default function AskConnexDrawer({
    open,
    instantOpen,
    isMobile,
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
    onOpenChange,
    onOpenChangeComplete,
    onSelectSession,
    onNewChat,
    onRename,
    onArchive,
    onRetry,
    onComposerChange,
    onRemoveAttachment,
    onSend,
}: AskConnexDrawerProps) {
    const reduceMotion = useReducedMotion() ?? false;
    const endRef = useRef<HTMLDivElement>(null);
    const [renameOpen, setRenameOpen] = useState(false);
    const [renameValue, setRenameValue] = useState('');
    const [renaming, setRenaming] = useState(false);
    const working = turn.phase === 'accepted' || turn.phase === 'running';
    const canSend = composer.trim().length > 0
        && loadState === 'ready'
        && !contextOverflow
        && !contentTooLong
        && !working
        && unavailable === null;

    useEffect(() => {
        endRef.current?.scrollIntoView({ block: 'end' });
    }, [messages, turn.phase]);

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

    return (
        <>
            <Drawer
                open={open}
                onOpenChange={onOpenChange}
                onOpenChangeComplete={onOpenChangeComplete}
                modal={isMobile}
                disablePointerDismissal
                swipeDirection={isMobile ? 'down' : 'right'}
                showSwipeHandle={isMobile}
                motionClassName={instantOpen
                    ? 'duration-0'
                    : reduceMotion
                      ? 'transition-opacity duration-150 data-starting-style:opacity-0 data-ending-style:opacity-0 motion-reduce:transition-opacity'
                      : undefined}
            >
                <DrawerContent
                    showCloseButton={false}
                    aria-describedby={undefined}
                    className="h-[calc(100dvh-1rem)] max-h-[calc(100dvh-1rem)] w-full gap-0 rounded-t-2xl p-0 md:h-full md:max-h-none md:w-[min(28rem,calc(100vw-2rem))] md:max-w-none md:rounded-2xl lg:w-[32rem]"
                >
                    <DrawerDescription className="sr-only">{labels.title}</DrawerDescription>
                    <DrawerHeader className="flex-row items-center gap-1 border-b border-border px-3 py-2">
                        <DrawerTitle className="min-w-0 flex-1">
                            <DropdownMenu>
                                <DropdownMenuTrigger asChild>
                                    <Button variant="ghost" className="min-w-0 max-w-full justify-start">
                                        <span className="truncate">{activeSession?.title ?? labels.newChat}</span>
                                        <ChevronDownIcon data-icon="inline-end" className="size-3.5" />
                                    </Button>
                                </DropdownMenuTrigger>
                                <DropdownMenuContent align="start" className="min-w-64">
                                    <DropdownMenuLabel>{labels.recentSessions}</DropdownMenuLabel>
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
                                </DropdownMenuContent>
                            </DropdownMenu>
                        </DrawerTitle>

                        <DropdownMenu>
                            <DropdownMenuTrigger asChild>
                                <Button variant="ghost" size="icon-sm" aria-label={labels.moreOptions}>
                                    <EllipsisHorizontalIcon className="size-4" />
                                </Button>
                            </DropdownMenuTrigger>
                            <DropdownMenuContent align="end">
                                <DropdownMenuItem
                                    disabled={!activeSession?.ownedByCurrentUser}
                                    onSelect={beginRename}
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

                        <DrawerClose render={<Button variant="ghost" size="icon-sm" aria-label={labels.close} />}>
                            <XMarkIcon className="size-4" />
                        </DrawerClose>
                    </DrawerHeader>

                    <ContextStrip
                        implicitContext={implicitContext}
                        attachments={attachments}
                        overflow={contextOverflow}
                        labels={labels}
                        onRemove={onRemoveAttachment}
                    />

                    <ScrollArea className="min-h-0 flex-1">
                        {loadState === 'loading' ? <TranscriptSkeleton /> : null}
                        {loadState === 'error' && loadError ? (
                            <ErrorState error={loadError} retry={onRetry} showBack={false} />
                        ) : null}
                        {loadState === 'forbidden' ? (
                            <AccessDenied variant="inline" title={unavailable?.title} body={unavailable?.body ?? ''} />
                        ) : null}
                        {loadState === 'ready' && messages.length === 0 ? (
                            <EmptyState
                                icon={SparklesIcon}
                                title={labels.emptyTitle}
                                body={labels.emptyBody}
                                tone="brand"
                                className="border-0 bg-transparent px-4 py-12"
                                action={
                                    <div className="flex w-full max-w-sm flex-col gap-2">
                                        {starterPrompts.map((prompt) => (
                                            <Button
                                                key={prompt}
                                                type="button"
                                                variant="outline"
                                                className="h-auto justify-start whitespace-normal py-2 text-left"
                                                onClick={() => onComposerChange(prompt)}
                                            >
                                                {prompt}
                                            </Button>
                                        ))}
                                    </div>
                                }
                            />
                        ) : null}
                        {loadState === 'ready' && messages.length > 0 ? (
                            <div className="px-4">
                                {messages.map((message) => (
                                    <TranscriptMessage
                                        key={message.id}
                                        message={message}
                                        fresh={freshMessageIds.has(message.id)}
                                    />
                                ))}
                            </div>
                        ) : null}
                        <div ref={endRef} />
                    </ScrollArea>

                    <TurnActivity turn={turn} labels={labels} />

                    {unavailable ? (
                        <div className="border-t border-border p-4">
                            <AccessDenied variant="inline" title={unavailable.title} body={unavailable.body} />
                        </div>
                    ) : (
                        <form
                            className="shrink-0 border-t border-border p-3"
                            onSubmit={(event) => {
                                event.preventDefault();
                                if (canSend) onSend();
                            }}
                        >
                            <div data-base-ui-swipe-ignore className="rounded-xl border border-input bg-background p-2 focus-within:ring-2 focus-within:ring-ring/50">
                                <MentionEditor
                                    value={composer}
                                    onChange={onComposerChange}
                                    onSubmit={() => {
                                        if (canSend) onSend();
                                    }}
                                    placeholder={labels.composerPlaceholder}
                                    ariaLabel={labels.composerAria}
                                    mentionTypes={ASK_CONNEX_MENTION_TYPES}
                                    className="min-h-20 max-h-36 overflow-y-auto px-1 py-1 text-sm leading-5"
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
                                        <PaperAirplaneIcon className="size-4" />
                                    </Button>
                                </div>
                            </div>
                        </form>
                    )}
                </DrawerContent>
            </Drawer>

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
