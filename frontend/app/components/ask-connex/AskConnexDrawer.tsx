'use client';

import {
    Fragment,
    useCallback,
    useEffect,
    useId,
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
    CheckCircleIcon,
    ArrowRightStartOnRectangleIcon,
    ArrowDownIcon,
    ArrowUpIcon,
    ArrowsPointingInIcon,
    Bars3BottomLeftIcon,
    CheckIcon,
    ChevronDoubleLeftIcon,
    ChevronDoubleRightIcon,
    ChevronDownIcon,
    ClockIcon,
    EllipsisHorizontalIcon,
    ExclamationCircleIcon,
    ExclamationTriangleIcon,
    FunnelIcon,
    HandRaisedIcon,
    LinkIcon,
    LockClosedIcon,
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
import MentionEditor, {
    type MentionEditorHandle,
} from '@/app/components/activity/notes/MentionEditor';
import AskConnexMarkdown from '@/app/components/ask-connex/AskConnexMarkdown';
import {
    AskConnexContextStrip,
    AskConnexScopeNotice,
    hasAskConnexContextInputs,
    type AskConnexContextLabels,
} from '@/app/components/ask-connex/AskConnexContextCockpit';
import AskConnexScopeEditor from '@/app/components/ask-connex/AskConnexScopeEditor';
import AskConnexTab from '@/app/components/ask-connex/AskConnexTab';
import AskConnexProposalReview, {
    AskConnexProposalReviewSummary,
    type AskConnexProposalReviewLabels,
} from '@/app/components/ask-connex/AskConnexProposalReview';
import AskConnexToolCard, {
    type AskConnexToolCardLabels,
} from '@/app/components/ask-connex/AskConnexToolCard';
import type {
    AskConnexAttachment,
    AskConnexFileAttachment,
    AskConnexRequestScope,
    AskConnexSelectionContext,
    AskConnexToolAction,
    AskConnexToolCardState,
    AskConnexTurnSegment,
    AskConnexTurnState,
} from '@/app/lib/askConnex';
import {
    EMPTY_ASK_CONNEX_ALLOWED_RECORDS,
    anchorAskConnexToolCards,
    appendAskConnexPrompt,
    askConnexCitationHref,
    askConnexCitations,
    askConnexGroupedToolCallIds,
    askConnexMessageNarration,
    askConnexMessageTodos,
    askConnexPromptFocusPending,
    askConnexProposalGroups,
    askConnexSendable,
    askConnexTranscript,
    groupAskConnexMessages,
    hasPendingAskConnexFileOperation,
    latestAskConnexSuggestions,
    toggleAskConnexProposalExclusion,
} from '@/app/lib/askConnex';
import type {
    AskConnexScopeChip,
    AskConnexScopeDraft,
    AskConnexScopePreviewState,
} from '@/app/lib/askConnexScope';
import {
    ASK_CONNEX_WIDTHS,
    askConnexRecovery,
    askConnexSessionActivity,
    askConnexSessionReaders,
    askConnexTerminalKind,
    askConnexTimedOutMessage,
    askConnexWidthLength,
    filterAskConnexSessions,
    groupAskConnexSessions,
    type AskConnexActiveState,
    type AskConnexFailureMessage,
    type AskConnexRecovery,
    type AskConnexSessionGroupKey,
    type AskConnexWidth,
} from '@/app/lib/askConnexSurface';
import type { AskConnexStreamStore } from '@/app/lib/askConnexStream';
import { durationMicro, easeOut, instant, springSmooth } from '@/app/lib/motion';
import type {
    AiAssistantSkill,
    AiChatCitation,
    AiChatMessage,
    AiChatTodo,
    AiChatParticipant,
    AiChatPresence,
    AiChatSession,
    WorkspaceMember,
} from '@/app/lib/types';
import { Avatar, AvatarFallback, AvatarGroup, AvatarImage } from '@/components/ui/avatar';
import { Badge } from '@/components/ui/badge';
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
import { SegmentedControl } from '@/components/ui/segmented-control';
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
import { Separator } from '@/components/ui/separator';
import { Skeleton } from '@/components/ui/skeleton';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { cn } from '@/lib/utils';
import AskConnexCommandCenter from '@/app/components/ask-connex/AskConnexCommandCenter';

type DrawerLoadState = 'loading' | 'ready' | 'error' | 'forbidden';

const ASK_CONNEX_MENTION_TYPES = {
    '@': ['person'],
    '#': ['company', 'deal'],
} as const;

type UnavailableState = {
    title: string;
    body: string;
} | null;

const noopRecovery = () => {};

const EMPTY_TURN_SEGMENTS: readonly AskConnexTurnSegment[] = [];
const EMPTY_TODO_PLAN: readonly AiChatTodo[] = [];

/** A settled answer with no route out: the member stopped it themselves. */
const NO_RECOVERY: AskConnexRecovery = {
    retry: false,
    continueFromPartial: false,
    narrowScope: false,
    narrowScopeFirst: false,
};

/**
 * The vocabulary the live and settled answer surfaces need: the streamed tail, its partial-answer
 * disclosure, and the status line.
 */
export type AskConnexTurnLabels = {
    assistantAuthor: string;
    continueFromPartial: string;
    narrowScope: string;
    partialAnswer: string;
    retry: string;
    stop: string;
    stopping: string;
    /** One sentence per classified failure, so a newly classified reason cannot go unexplained. */
    terminalMessage: Record<AskConnexFailureMessage, string>;
    turnAccepted: string;
    turnCancelled: string;
    turnResolved: string;
    turnStreaming: string;
    turnWorking: string;
    /** Accessible name for the disclosure that expands the reasoning panel. */
    thinkingToggle: string;
    /** Accessible name for the same disclosure once the reasoning panel is open. */
    thinkingToggleHide: string;
    /** Accessible name for the list of narration segments leading up to an answer. */
    narrationTrail: string;
    /** Accessible name for the plan the assistant published for a turn. */
    todoPlan: string;
    /** How each plan step's state is named for readers who never see its glyph. */
    todoStatus: Record<AiChatTodo['status'], string>;
};

/**
 * One job this surface can offer, already resolved into the member's language.
 *
 * Offers come from the server's capability directory, so the list is what Ask Connex can actually
 * do here rather than a set of prompts this client invented.
 */
export type AskConnexJobOffer = {
    id: string;
    label: string;
    prompt: string;
};

/** The declared-filter surface: what is set, what it turned out to cover, and how to change it. */
export type AskConnexScopeSurface = {
    draft: AskConnexScopeDraft;
    editorOpen: boolean;
    /** The caller's capability directory, so the preview can name what would run. */
    skills: readonly AiAssistantSkill[];
    filterCount: number;
    chips: AskConnexScopeChip[];
    preview: AskConnexScopePreviewState;
    /** A refused scope, already stated in plain language, or null when nothing was refused. */
    refusal: string | null;
    /** Whether the filters as set cannot be sent, so the request waits rather than losing them. */
    blocked: boolean;
    /** The problem holding the request back, already stated in plain language, or null. */
    problem: string | null;
    onDraftChange: (draft: AskConnexScopeDraft) => void;
    onEditorOpenChange: (open: boolean) => void;
    /** Takes the question the breadth check should be about, at the moment one is being decided. */
    onSettle: (content?: string) => void;
};

type AskConnexDrawerLabels = AskConnexContextLabels & AskConnexTurnLabels & {
    addContext: string;
    addRecordContext: string;
    archive: string;
    dismissSuggestions: string;
    suggestions: string;
    attachFile: string;
    citations: string;
    disclosureCreation: string;
    disclosureList: string;
    imageDisclosure: string;
    citationKind: (kind: AiChatCitation['kind']) => string;
    /** The freshness line a citation pill discloses, from its label and its declared instant. */
    citationFreshness: (label: string, instant: string) => string;
    /** The disclosure for a citation whose source carries no timestamp at all. */
    citationFreshnessUnknown: (label: string) => string;
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
    width: string;
    widthCompact: string;
    widthComfortable: string;
    visibilityPrivate: string;
    visibilityShared: string;
    stateRunning: string;
    stateAwaitingApproval: string;
    stateFailed: string;
    contextSummary: (count: number) => string;
    participantCount: (count: number) => string;
    sessionActivity: (time: string) => string;
    sessionRail: string;
    sessionGroup: (key: AskConnexSessionGroupKey) => string;
    relativeTime: (instant: string) => string;
    toolCard: AskConnexToolCardLabels;
    proposalReview: AskConnexProposalReviewLabels;
};

/**
 * Everything the review surfaces need that is not the cards themselves.
 *
 * Carried as one value rather than as a handful of parallel props because the batch, the way it is
 * changed, and the way it is committed are one contract: a surface that can show the grouped review
 * can also change and apply it, and a surface that only summarises it can do neither.
 */
export type AskConnexToolReview = {
    /** Whether this mount is the full workspace, which reviews the batch instead of announcing it. */
    workspace: boolean;
    excludedToolCallIds: ReadonlySet<number>;
    labels: AskConnexProposalReviewLabels;
    formatDeadline: (instant: string) => string;
    formatRemaining: (instant: string) => string;
    onToggleInclusion: (toolCallId: number) => void;
    onApplySelected: (toolCallIds: number[]) => Promise<void>;
    onOpenFullView: () => void;
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
    /** Everything the next request will read: the records it carries and the filters it declares. */
    requestScope: AskConnexRequestScope;
    scope: AskConnexScopeSurface;
    attachments: AskConnexAttachment[];
    fileAttachments: AskConnexFileAttachment[];
    canAttachFiles: boolean;
    canRemoveFiles: boolean;
    contextOverflow: boolean;
    contentTooLong: boolean;
    working: boolean;
    turn: AskConnexTurnState;
    /** The active turn's ephemeral reasoning steps, in step order; empty for everyone but the requester. */
    thinking: readonly AskConnexTurnSegment[];
    /** The active turn's narration so far, in step order; empty for everyone but the requester. */
    narration: readonly AskConnexTurnSegment[];
    /** The plan the active turn published, in its current state. */
    todos: readonly AiChatTodo[];
    streamStore: AskConnexStreamStore;
    streaming: boolean;
    cancelling: boolean;
    toolCalls: AskConnexToolCardState[];
    actionableToolCallIds: ReadonlySet<number>;
    canRetryTurn: boolean;
    /**
     * The message that continues a stopped answer, already composed from the question that produced
     * it, or null when nothing was retained to continue from. The surface hands it to the composer
     * for the member to read and edit rather than sending it, because it is an ordinary question and
     * the member is the one asking it.
     */
    continuePrompt: string | null;
    width: AskConnexWidth;
    activeState: AskConnexActiveState;
    contextCount: number;
    /** The shared render clock, so every relative time in the rail agrees within one frame. */
    now: number;
    unavailable: UnavailableState;
    /** The jobs this surface offers, from the server's capability directory. */
    jobs: AskConnexJobOffer[];
    /**
     * The outstanding job request, or zero when there is none. Raised whenever a contextual entry
     * point writes a job into the composer, so the composer takes focus with the caret after it and
     * the member lands where they can edit and send.
     */
    promptRequest: number;
    /**
     * Marks the outstanding request as honoured. Owned by the caller rather than the surface, so a
     * surface that mounts afterwards — a phone reopening a panel it does not keep mounted — has
     * nothing left to replay.
     */
    onPromptConsumed: () => void;
    labels: AskConnexDrawerLabels;
    onWidthChange: (width: AskConnexWidth) => void;
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
    onToolAction: (toolCallId: number, action: AskConnexToolAction) => Promise<void>;
    /**
     * Applies a reviewed batch as one decision.
     *
     * Held apart from the single-proposal path because a batch is not N independent presses: the
     * requests are sequenced so each row reports its own outcome against settled state, and the
     * page behind the conversation is refreshed once at the end rather than once per change.
     */
    onToolActions: (
        toolCallIds: readonly number[],
        action: AskConnexToolAction,
    ) => Promise<void>;
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
    /** Whether this mount can offer the width control — the routed workspace has no width to pick. */
    resizable: boolean;
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
            {visible.map((citation) => {
                const label = citation.label ?? labels.citationKind(citation.kind);
                return (
                    <li key={`${citation.kind}:${citation.id}`}>
                        <Tooltip>
                            <TooltipTrigger asChild>
                                <Link
                                    href={askConnexCitationHref(citation)}
                                    className="inline-flex max-w-56 items-center gap-1.5 rounded-full border border-border px-2.5 py-1 text-xs text-muted-foreground transition-colors hover:bg-muted hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
                                >
                                    <LinkIcon className="size-3 shrink-0" />
                                    <span className="truncate">{label}</span>
                                </Link>
                            </TooltipTrigger>
                            <TooltipContent>
                                {citation.asOf
                                    ? labels.citationFreshness(label, citation.asOf)
                                    : labels.citationFreshnessUnknown(label)}
                            </TooltipContent>
                        </Tooltip>
                    </li>
                );
            })}
        </ul>
    );
}

/**
 * The jobs this page supports, offered where the member is about to type.
 *
 * Quiet, bounded, and dismissible: it is a reminder of what Ask Connex can do here, not a gallery
 * of prompts, so it disappears the moment anything is typed and never stands between the member and
 * the composer. Choosing one writes the question into the composer and leaves the sending to them.
 */
function JobSuggestions({
    jobs,
    label,
    dismissLabel,
    onUse,
    onDismiss,
}: {
    jobs: AskConnexJobOffer[];
    label: string;
    dismissLabel: string;
    onUse: (job: AskConnexJobOffer) => void;
    onDismiss: () => void;
}) {
    const reduceMotion = useReducedMotion() ?? false;
    if (jobs.length === 0) return null;

    return (
        <motion.div
            role="group"
            aria-label={label}
            initial={reduceMotion ? { opacity: 0 } : { opacity: 0, transform: 'translateY(0.25rem)' }}
            animate={{ opacity: 1, transform: 'translateY(0rem)' }}
            transition={reduceMotion ? instant : { duration: durationMicro, ease: easeOut }}
            className="mb-2 flex min-w-0 items-center gap-1.5"
        >
            <div
                data-base-ui-swipe-ignore
                className="flex min-w-0 flex-1 items-center gap-1.5 overflow-x-auto"
            >
                {jobs.map((job) => (
                    <Button
                        key={job.id}
                        type="button"
                        size="inline"
                        variant="outline"
                        className="shrink-0 font-normal"
                        onClick={() => onUse(job)}
                    >
                        {job.label}
                    </Button>
                ))}
            </div>
            <IconButton
                type="button"
                variant="ghost"
                size="icon-inline"
                label={dismissLabel}
                className="shrink-0"
                onClick={onDismiss}
            >
                <XMarkIcon className="size-3" />
            </IconButton>
        </motion.div>
    );
}

function MessageSuggestions({
    suggestions,
    label,
    disabled,
    onSend,
}: {
    suggestions: string[];
    label: string;
    disabled: boolean;
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
                        disabled={disabled}
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

/**
 * The work the assistant narrated on its way to an answer, one quiet line per step.
 *
 * Narration is prose the assistant writes for the member who asked — what it is about to do, and
 * what it just found — so it sits in the transcript above the answer rather than inside the private
 * reasoning panel. The treatment is deliberately lighter than an answer bubble: this is the trail
 * that leads to the reply, not the reply, and a reader skimming for the answer has to be able to
 * tell the two apart at a glance.
 *
 * Segments render as Markdown for the same reason the answer does — the model writes prose, and
 * emphasis or a short list should read the way it reads in the reply. No segment ever renders a
 * record chip: narration is a status line rather than an evidence surface, and the server strips
 * record links to their labels before persisting a segment, so link syntax reaching this component
 * is by definition not an authorized citation. The allowlist is therefore fixed empty here rather
 * than passed in, which makes a caller that means well but supplies the answer's citations unable
 * to turn a status line into evidence.
 */
export function NarrationTrail({
    segments,
    label,
}: {
    segments: readonly AskConnexTurnSegment[];
    label: string;
}) {
    if (segments.length === 0) return null;
    return (
        <ul aria-label={label} className="space-y-1">
            {segments.map((segment) => (
                <li
                    key={segment.seq}
                    className="rounded-xl border border-border/60 bg-muted/40 px-3 py-1.5 text-xs leading-relaxed text-muted-foreground"
                >
                    <AskConnexMarkdown
                        content={segment.text}
                        allowedRecords={EMPTY_ASK_CONNEX_ALLOWED_RECORDS}
                    />
                </li>
            ))}
        </ul>
    );
}

/**
 * The plan the assistant published for a turn, one line per step.
 *
 * A checklist rather than prose: a reader scanning a long turn should be able to see what is done,
 * what is running, and what is left without reading a word of narration. The list is the model's
 * own working plan, so it renders as plain text — a step is a label, never a link or a claim about
 * a record.
 *
 * Only a live plan animates its running step. A settled answer keeps whatever status the model
 * last published — that is what it said, and rewriting it would be inventing an outcome — but a
 * finished turn must not keep spinning as though work were still under way. Each step also carries
 * its status as text for readers who never see the glyph.
 */
export function TodoPlan({
    todos,
    label,
    statusLabels,
    live = false,
}: {
    todos: readonly AiChatTodo[];
    label: string;
    statusLabels: Record<AiChatTodo['status'], string>;
    live?: boolean;
}) {
    if (todos.length === 0) return null;
    const seen = new Map<string, number>();
    return (
        <ul aria-label={label} className="grid gap-1 rounded-xl border border-border/60 bg-muted/30 px-3 py-2">
            {todos.map((todo) => {
                const occurrence = seen.get(todo.label) ?? 0;
                seen.set(todo.label, occurrence + 1);
                return (
                    <li
                        key={`${todo.label}#${occurrence}`}
                        className="flex items-start gap-2 text-xs leading-relaxed"
                    >
                        {todo.status === 'done' ? (
                            <CheckCircleIcon className="mt-0.5 size-3.5 shrink-0 text-brand" aria-hidden />
                        ) : todo.status === 'active' ? (
                            <ArrowPathIcon
                                className={cn(
                                    'mt-0.5 size-3.5 shrink-0 text-foreground',
                                    live && 'animate-spin motion-reduce:animate-none',
                                )}
                                aria-hidden
                            />
                        ) : (
                            <span
                                className="mt-1 size-2 shrink-0 rounded-full border border-muted-foreground/50"
                                aria-hidden
                            />
                        )}
                        <span
                            className={cn(
                                todo.status === 'done'
                                    ? 'text-muted-foreground line-through decoration-muted-foreground/40'
                                    : todo.status === 'active'
                                        ? 'font-medium text-foreground'
                                        : 'text-muted-foreground',
                            )}
                        >
                            <span className="sr-only">{statusLabels[todo.status]}: </span>
                            {todo.label}
                        </span>
                    </li>
                );
            })}
        </ul>
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
    review,
    suggestionsDisabled,
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
    review: AskConnexToolReview;
    suggestionsDisabled: boolean;
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
    const allowedRecords = useMemo<ReadonlySet<string>>(
        () => new Set(
            (message.citations ?? []).map((citation) => `${citation.kind}:${citation.id}`),
        ),
        [message.citations],
    );
    const narration = useMemo(() => askConnexMessageNarration(message), [message]);
    const todos = useMemo(() => askConnexMessageTodos(message), [message]);
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
                {!user && message.contentWithheld !== true && todos.length > 0 ? (
                    <TodoPlan
                        todos={todos}
                        label={labels.todoPlan}
                        statusLabels={labels.todoStatus}
                    />
                ) : null}
                {!user && message.contentWithheld !== true && narration.length > 0 ? (
                    <NarrationTrail segments={narration} label={labels.narrationTrail} />
                ) : null}
                <motion.div
                    initial={animateEntrance ? (reduceMotion ? { opacity: 0 } : { opacity: 0, transform: 'translateY(0.375rem)' }) : false}
                    animate={{ opacity: 1, transform: 'translateY(0rem)' }}
                    transition={reduceMotion ? instant : { duration: 0.2, ease: easeOut }}
                    className={cn(
                        'break-words rounded-2xl px-3.5 py-2.5 text-sm leading-relaxed',
                        message.contentWithheld === true
                            ? 'bg-muted text-muted-foreground italic'
                            : user
                            ? 'whitespace-pre-wrap bg-primary text-primary-foreground'
                            : 'bg-muted text-foreground',
                    )}
                >
                    {message.contentWithheld === true
                        ? labels.contentWithheld
                        : user
                            ? message.content
                            : <AskConnexMarkdown content={message.content} allowedRecords={allowedRecords} />}
                </motion.div>
                {!user && message.contentWithheld !== true
                    ? <MessageCitations citations={message.citations} labels={labels} />
                    : null}
                {!user && message.contentWithheld !== true ? (
                    <ToolCallCards
                        cards={toolCalls}
                        labels={labels.toolCard}
                        actionsDisabled={false}
                        actionableToolCallIds={actionableToolCallIds}
                        review={review}
                        onAction={onToolAction}
                    />
                ) : null}
                {!user && message.contentWithheld !== true ? (
                    <MessageSuggestions
                        suggestions={suggestions}
                        label={labels.suggestedFollowUps}
                        disabled={suggestionsDisabled}
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

/**
 * One answer's write actions, as either a set of cards or one review of several changes.
 *
 * An answer that proposes a single change shows that change on its own card, where it belongs.
 * An answer that proposes several is a different decision — one the member makes about all of
 * them — so the cards give way to a review that counts them, and the drawer announces that review
 * rather than trying to hold it. Everything already decided, and everything the member is only
 * watching, keeps its own card either way.
 */
function ToolCallCards({
    cards,
    labels,
    actionsDisabled,
    actionableToolCallIds,
    review,
    onAction,
}: {
    cards: AskConnexToolCardState[];
    labels: AskConnexToolCardLabels;
    actionsDisabled: boolean;
    actionableToolCallIds: ReadonlySet<number>;
    review: AskConnexToolReview;
    onAction: (toolCallId: number, action: AskConnexToolAction) => void;
}) {
    const groups = askConnexProposalGroups(
        cards, actionableToolCallIds, review.excludedToolCallIds,
    );
    const grouped = askConnexGroupedToolCallIds(groups);
    const ungrouped = cards.filter((card) => !grouped.has(card.id));
    if (cards.length === 0) return null;
    return (
        <div className="space-y-2">
            {groups.map((group) => (review.workspace ? (
                <AskConnexProposalReview
                    key={group.turnId}
                    group={group}
                    labels={review.labels}
                    cardLabels={labels}
                    actionsDisabled={actionsDisabled}
                    onToggleInclusion={review.onToggleInclusion}
                    onAction={onAction}
                    onApplySelected={review.onApplySelected}
                />
            ) : (
                <AskConnexProposalReviewSummary
                    key={group.turnId}
                    group={group}
                    labels={review.labels}
                    onOpenFullView={review.onOpenFullView}
                />
            )))}
            {ungrouped.map((card) => (
                <AskConnexToolCard
                    key={card.id}
                    card={card}
                    labels={labels}
                    actionsDisabled={actionsDisabled || !actionableToolCallIds.has(card.id)}
                    onAction={onAction}
                    formatDeadline={review.formatDeadline}
                    formatRemaining={review.formatRemaining}
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
 * A settled answer that produced no transcript message: what happened, and the one action worth
 * offering next.
 *
 * The announcement is deliberately kept to the one-line outcome: the retry control sits outside it
 * so settling does not read a whole list aloud.
 */
function SettledTurnActivity({
    icon,
    message,
    destructive,
    labels,
    recovery,
    thinkingToggle = null,
    thinkingPanel = null,
    onRetry,
    onContinueFromPartial,
    onNarrowScope,
}: {
    icon: ReactNode;
    message: string;
    destructive?: boolean;
    labels: AskConnexTurnLabels;
    recovery: AskConnexRecovery;
    /** Opens the reasoning this turn produced before it stopped, when it produced any. */
    thinkingToggle?: ReactNode;
    thinkingPanel?: ReactNode;
    onRetry: () => void;
    onContinueFromPartial: () => void;
    onNarrowScope: () => void;
}) {
    const narrow = recovery.narrowScope ? (
        <Button
            key="narrow"
            type="button"
            variant={recovery.narrowScopeFirst ? 'outline' : 'ghost'}
            size="inline"
            onClick={onNarrowScope}
        >
            <ArrowsPointingInIcon />
            {labels.narrowScope}
        </Button>
    ) : null;
    const continueFromPartial = recovery.continueFromPartial ? (
        <Button key="continue" type="button" variant="ghost" size="inline" onClick={onContinueFromPartial}>
            <Bars3BottomLeftIcon />
            {labels.continueFromPartial}
        </Button>
    ) : null;
    const retry = recovery.retry ? (
        <Button key="retry" type="button" variant="outline" size="inline" onClick={onRetry}>
            <ArrowPathIcon />
            {labels.retry}
        </Button>
    ) : null;
    const routes = recovery.narrowScopeFirst
        ? [narrow, continueFromPartial, retry]
        : [retry, continueFromPartial, narrow];
    const offered = routes.filter((route) => route !== null);

    return (
        <div className="space-y-2 px-4 py-2 text-xs text-muted-foreground">
            <div
                role="status"
                className={cn('flex items-start gap-2', destructive && 'text-destructive')}
            >
                <span className="mt-px shrink-0">{icon}</span>
                <span className="leading-relaxed">{message}</span>
            </div>
            {offered.length > 0 ? (
                <div className="flex flex-wrap items-center gap-1.5">{offered}</div>
            ) : null}
            {thinkingToggle}
            {thinkingPanel}
        </div>
    );
}

/**
 * The live and settled state of the answer in progress.
 *
 * While the answer runs, the whole region is the announced status so a phase change is spoken as it
 * lands; the streamed words themselves are never announced.
 *
 * The status line doubles as a disclosure once the first ephemeral reasoning step arrives: until
 * then there is nothing to show, so the line stays a plain line rather than offering an empty
 * panel. Reasoning is plain text — it is not Markdown and is never parsed as such — and it outlives
 * the turn that produced it: a settled phase offers the same disclosure, because a reader watching
 * an answer arrive is not simultaneously reading the reasoning behind it, and this client's memory
 * holds the only copy there will ever be. Expansion is remembered per turn rather than per mount,
 * so a new question always starts collapsed, and the expanded panel sits outside the announced
 * status region — a live region that re-read the whole accumulated reasoning on every step would
 * drown out the status it exists for.
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
    hasPartial = false,
    thinking = EMPTY_TURN_SEGMENTS,
    labels,
    onCancel,
    onRetry,
    onContinueFromPartial = noopRecovery,
    onNarrowScope = noopRecovery,
}: {
    turn: AskConnexTurnState;
    streaming: boolean;
    cancelling: boolean;
    canRetry: boolean;
    /** Whether words were retained from this answer before it stopped. */
    hasPartial?: boolean;
    /** The active turn's ephemeral reasoning steps, in step order; only the requester has any. */
    thinking?: readonly AskConnexTurnSegment[];
    labels: AskConnexTurnLabels;
    onCancel: () => void;
    onRetry: () => void;
    onContinueFromPartial?: () => void;
    onNarrowScope?: () => void;
}) {
    const [openForTurn, setOpenForTurn] = useState<number | null>(null);
    const thinkingPanelId = useId();
    const thinkingOpen = turn.turnId !== null && openForTurn === turn.turnId;
    const recovery = askConnexRecovery(turn.phase, turn.reason, canRetry, hasPartial);
    const toggleThinking = () => setOpenForTurn(thinkingOpen ? null : turn.turnId);
    const thinkingPanel = thinkingOpen && thinking.length > 0 ? (
        <div
            id={thinkingPanelId}
            role="region"
            aria-label={labels.thinkingToggle}
            className="max-h-48 divide-y divide-border/60 overflow-y-auto rounded-lg bg-muted/60 px-3 py-1 text-xs leading-relaxed break-words whitespace-pre-wrap text-muted-foreground"
        >
            {thinking.map((entry) => (
                <p key={entry.seq} className="py-1.5">{entry.text}</p>
            ))}
        </div>
    ) : null;
    const settledThinkingToggle = thinking.length > 0 ? (
        <button
            type="button"
            aria-expanded={thinkingOpen}
            aria-controls={thinkingPanelId}
            onClick={toggleThinking}
            className="flex items-center gap-1.5 rounded-md text-left transition-colors hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
        >
            <span>{thinkingOpen ? labels.thinkingToggleHide : labels.thinkingToggle}</span>
            <ChevronDownIcon
                aria-hidden
                className={cn(
                    'size-3 shrink-0 transition-transform motion-reduce:transition-none',
                    thinkingOpen && 'rotate-180',
                )}
            />
        </button>
    ) : null;
    if (turn.phase === 'idle') return null;
    if (turn.phase === 'resolved') {
        return (
            <div className="space-y-2 px-4 py-2 text-xs text-muted-foreground">
                <div role="status" className="flex items-center gap-2">
                    <CheckIcon className="size-3.5 text-primary" />
                    <span>{labels.turnResolved}</span>
                </div>
                {settledThinkingToggle}
                {thinkingPanel}
            </div>
        );
    }
    if (turn.phase === 'failed') {
        return (
            <SettledTurnActivity
                icon={<ExclamationCircleIcon className="size-3.5" />}
                destructive
                message={labels.terminalMessage[askConnexTerminalKind(turn.reason).message]}
                labels={labels}
                recovery={recovery}
                thinkingToggle={settledThinkingToggle}
                thinkingPanel={thinkingPanel}
                onRetry={onRetry}
                onContinueFromPartial={onContinueFromPartial}
                onNarrowScope={onNarrowScope}
            />
        );
    }
    if (turn.phase === 'timed_out') {
        return (
            <SettledTurnActivity
                icon={<ClockIcon className="size-3.5" />}
                message={labels.terminalMessage[askConnexTimedOutMessage(turn.reason)]}
                labels={labels}
                recovery={recovery}
                thinkingToggle={settledThinkingToggle}
                thinkingPanel={thinkingPanel}
                onRetry={onRetry}
                onContinueFromPartial={onContinueFromPartial}
                onNarrowScope={onNarrowScope}
            />
        );
    }
    if (turn.phase === 'cancelled') {
        return (
            <SettledTurnActivity
                icon={<StopCircleIcon className="size-3.5" />}
                message={labels.turnCancelled}
                labels={labels}
                recovery={NO_RECOVERY}
                thinkingToggle={settledThinkingToggle}
                thinkingPanel={thinkingPanel}
                onRetry={onRetry}
                onContinueFromPartial={onContinueFromPartial}
                onNarrowScope={onNarrowScope}
            />
        );
    }
    const statusText = cancelling
        ? labels.stopping
        : turn.phase === 'accepted'
            ? labels.turnAccepted
            : streaming
                ? labels.turnStreaming
                : labels.turnWorking;
    return (
        <div className="space-y-2 px-4 py-2 text-xs text-muted-foreground">
            <div role="status" className="flex items-center gap-2">
                {thinking.length > 0 ? (
                    <button
                        type="button"
                        aria-expanded={thinkingOpen}
                        aria-controls={thinkingPanelId}
                        onClick={toggleThinking}
                        className="flex items-center gap-2 rounded-md text-left transition-colors hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
                    >
                        <SparklesIcon className="size-3.5" />
                        <span>{statusText}</span>
                        <span className="sr-only">
                            {thinkingOpen ? labels.thinkingToggleHide : labels.thinkingToggle}
                        </span>
                        <ChevronDownIcon
                            aria-hidden
                            className={cn(
                                'size-3 shrink-0 transition-transform motion-reduce:transition-none',
                                thinkingOpen && 'rotate-180',
                            )}
                        />
                    </button>
                ) : (
                    <>
                        <SparklesIcon className="size-3.5" />
                        <span>{statusText}</span>
                    </>
                )}
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
            {thinkingPanel}
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
                        <div className="break-words rounded-2xl bg-muted px-3.5 py-2.5 text-sm leading-relaxed text-foreground">
                            <AskConnexMarkdown
                                content={snapshot.text}
                                allowedRecords={EMPTY_ASK_CONNEX_ALLOWED_RECORDS}
                            />
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

const ACTIVE_STATE_ICONS = {
    running: SparklesIcon,
    awaitingApproval: HandRaisedIcon,
    failed: ExclamationCircleIcon,
} as const;

/**
 * One fact about the chat, stated as a word and an icon.
 *
 * Every chip in the metadata row carries both channels, so nothing here is legible only to a reader
 * who can tell the hues apart, and the row reads the same at the drawer's narrowest width as it
 * does across the workspace.
 */
function HeaderChip({
    icon: Icon,
    label,
    tone,
    arrives,
}: {
    icon: typeof SparklesIcon;
    label: string;
    tone?: 'destructive';
    /**
     * Whether this chip comes and goes with the chat's state. One that does arrives rather than
     * appears, so a row that gains a chip mid-read does not look like the header changed shape on
     * its own; one that is always there has no arrival to show.
     */
    arrives?: boolean;
}) {
    const reduceMotion = useReducedMotion() ?? false;
    const chip = (
        <Badge
            variant="outline"
            className={cn('shrink-0 font-normal', tone === 'destructive' && 'text-destructive')}
        >
            <Icon aria-hidden />
            {label}
        </Badge>
    );
    if (!arrives) return chip;
    return (
        <motion.span
            className="inline-flex shrink-0"
            initial={reduceMotion
                ? { opacity: 0 }
                : { opacity: 0, transform: 'translateY(-0.125rem)' }}
            animate={{ opacity: 1, transform: 'translateY(0rem)' }}
            transition={reduceMotion ? instant : { duration: durationMicro, ease: easeOut }}
        >
            {chip}
        </motion.span>
    );
}

/**
 * The header both Ask Connex surfaces wear.
 *
 * Two tiers, in the order a reader needs them: what this chat is, then everything about it that
 * changes — who can see it, whether it is doing something right now, and how much it is carrying.
 * The state tier exists because none of those facts were previously readable without opening a
 * menu, scrolling the transcript, or both, and a member glancing at the header has to be able to
 * tell a private chat from a shared one and a working answer from a settled one.
 *
 * The width control and the handoff into the full workspace sit on the state tier rather than
 * beside the close control: both change which surface you are reading in, and neither belongs in
 * the row where a mis-aimed click closes the panel.
 *
 * The access line counts the members reading the chat rather than everyone the participants
 * endpoint returns; pending invitations stay in the sharing dialog, which names them as pending and
 * offers the controls that act on them.
 */
function SurfaceHeader({
    workspace,
    resizable,
    activeSession,
    participants,
    activeState,
    contextCount,
    width,
    labels,
    closeButton,
    sessionMenu,
    onWidthChange,
    onOpenWorkspace,
}: {
    workspace: boolean;
    resizable: boolean;
    activeSession: AiChatSession | null;
    participants: AiChatParticipant[];
    activeState: AskConnexActiveState;
    contextCount: number;
    width: AskConnexWidth;
    labels: AskConnexDrawerLabels;
    closeButton: ReactNode;
    sessionMenu: ReactNode;
    onWidthChange: (width: AskConnexWidth) => void;
    onOpenWorkspace: () => void;
}) {
    const shared = activeSession?.visibility === 'shared';
    const readers = askConnexSessionReaders(participants);
    const title = activeSession?.title ?? labels.newChat;
    const StateIcon = activeState === null ? null : ACTIVE_STATE_ICONS[activeState];
    const stateLabel = activeState === 'running'
        ? labels.stateRunning
        : activeState === 'awaitingApproval'
            ? labels.stateAwaitingApproval
            : labels.stateFailed;

    return (
        <header className="flex shrink-0 flex-col gap-1 border-b border-border px-3 py-2">
            <div className="flex min-h-8 items-center gap-1">
                {workspace ? (
                    <h1 className="min-w-0 flex-1 truncate px-2 text-sm font-medium text-foreground">
                        {title}
                    </h1>
                ) : (
                    <p className="min-w-0 flex-1 truncate px-2 text-sm font-medium text-foreground">
                        {title}
                    </p>
                )}
                {sessionMenu}
                {closeButton}
            </div>
            <div className="flex min-w-0 items-center gap-2 px-2">
                <div className="flex min-w-0 flex-1 flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
                    <HeaderChip
                        icon={shared ? UserGroupIcon : LockClosedIcon}
                        label={shared ? labels.visibilityShared : labels.visibilityPrivate}
                    />
                    {StateIcon !== null ? (
                        <HeaderChip
                            key={activeState}
                            icon={StateIcon}
                            label={stateLabel}
                            tone={activeState === 'failed' ? 'destructive' : undefined}
                            arrives
                        />
                    ) : null}
                    {contextCount > 0 ? (
                        <span className="truncate">{labels.contextSummary(contextCount)}</span>
                    ) : null}
                    {workspace && shared && readers.length > 0 ? (
                        <span className="flex min-w-0 items-center gap-1.5">
                            <AvatarGroup aria-label={labels.participants}>
                                {readers.map((participant) => (
                                    <Avatar
                                        key={participant.userId}
                                        size="sm"
                                        title={participant.displayName}
                                    >
                                        <AvatarImage src={participant.profilePictureUrl ?? undefined} alt="" />
                                        <AvatarFallback>
                                            {participant.displayName.slice(0, 1).toUpperCase()}
                                        </AvatarFallback>
                                    </Avatar>
                                ))}
                            </AvatarGroup>
                            <span className="truncate">{labels.participantCount(readers.length)}</span>
                        </span>
                    ) : null}
                </div>
                {resizable ? (
                    <>
                        <SegmentedControl
                            className="shrink-0"
                            size="inline"
                            ariaLabel={labels.width}
                            value={width}
                            onChange={onWidthChange}
                            options={ASK_CONNEX_WIDTHS.map((option) => ({
                                value: option,
                                icon: option === 'compact'
                                    ? <ChevronDoubleRightIcon />
                                    : <ChevronDoubleLeftIcon />,
                                ariaLabel: option === 'compact'
                                    ? labels.widthCompact
                                    : labels.widthComfortable,
                            }))}
                        />
                        <Button
                            type="button"
                            variant="outline"
                            size="inline"
                            className="shrink-0"
                            onClick={onOpenWorkspace}
                        >
                            <ArrowsPointingOutIcon />
                            {labels.openWorkspace}
                        </Button>
                    </>
                ) : null}
            </div>
        </header>
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
    requestScope,
    scope,
    attachments,
    fileAttachments,
    canAttachFiles,
    canRemoveFiles,
    contextOverflow,
    contentTooLong,
    working,
    turn,
    thinking,
    narration,
    todos: liveTodos,
    streamStore,
    streaming,
    cancelling,
    toolCalls,
    actionableToolCallIds,
    canRetryTurn,
    continuePrompt,
    width,
    activeState,
    contextCount,
    participants,
    now,
    unavailable,
    jobs,
    promptRequest,
    onPromptConsumed,
    labels,
    closeButton,
    resizable,
    onWidthChange,
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
    onToolActions,
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
    const composerRef = useRef<MentionEditorHandle>(null);
    const [recordPickerRequest, setRecordPickerRequest] = useState(0);
    const fileOperationPending = hasPendingAskConnexFileOperation(fileAttachments);
    const busy = working || fileOperationPending;
    /**
     * Proposals the member has taken out of a grouped review.
     *
     * Held here, beside the transcript, rather than inside the review: the cards are re-read
     * whenever the session refreshes, and a proposal the member deliberately took out of the batch
     * must stay out while that happens rather than quietly rejoining it.
     */
    const [excludedProposals, setExcludedProposals] = useState<ReadonlySet<number>>(
        () => new Set<number>(),
    );
    const surfaceFormat = useFormatter();
    const toolReview = useMemo<AskConnexToolReview>(() => ({
        workspace,
        excludedToolCallIds: excludedProposals,
        labels: labels.proposalReview,
        formatDeadline: (instant: string) => {
            const deadline = new Date(instant);
            return Number.isNaN(deadline.getTime())
                ? instant
                : surfaceFormat.dateTime(deadline, { hour: 'numeric', minute: '2-digit' });
        },
        formatRemaining: (instant: string) => {
            const deadline = new Date(instant);
            return Number.isNaN(deadline.getTime())
                ? instant
                : surfaceFormat.relativeTime(deadline, now);
        },
        onToggleInclusion: (toolCallId: number) => setExcludedProposals(
            (current) => toggleAskConnexProposalExclusion(current, toolCallId),
        ),
        onApplySelected: (toolCallIds: number[]) => onToolActions(toolCallIds, 'approve'),
        onOpenFullView: onOpenWorkspace,
    }), [
        excludedProposals,
        labels.proposalReview,
        now,
        onOpenWorkspace,
        onToolActions,
        surfaceFormat,
        workspace,
    ]);
    /**
     * Whether this answer left words behind when it stopped.
     *
     * Read from the streaming flag rather than by subscribing to the stream store: the store
     * publishes at animation-frame cadence while an answer is being written, and a subscription here
     * would re-render the whole transcript on every frame. The flag carries the same fact — it is
     * raised the first time text is published and lowered when the stream is discarded, which
     * happens for a resolved answer, whose words are the transcript, and for a turn that ended by
     * withdrawing this member's authority to read what it wrote.
     */
    const hasPartialAnswer = streaming;
    /**
     * The narration to show above the pending answer, which is only ever the running turn's.
     *
     * Once a turn settles its narration belongs to the message it produced, which carries the same
     * segments durably — so the live trail stands down at exactly the moment the settled one takes
     * over, and the reader never sees the same work narrated twice.
     */
    const liveNarration = turn.phase === 'accepted' || turn.phase === 'running'
        ? narration
        : EMPTY_TURN_SEGMENTS;
    /**
     * The plan to show above the pending answer, which is only ever the running turn's: a settled
     * turn's plan belongs to the message it produced, which carries the same list durably.
     */
    const liveTodoPlan = turn.phase === 'accepted' || turn.phase === 'running'
        ? liveTodos
        : EMPTY_TODO_PLAN;
    const suggestions = useMemo(
        () => latestAskConnexSuggestions(messages, busy),
        [busy, messages],
    );
    const latestMessageId = visibleMessages.at(-1)?.id ?? null;
    const historySummarized = transcript.historySummarized;
    /**
     * Whether to offer this page's jobs above the composer.
     *
     * Only once a conversation is under way — an empty chat already lists them in its empty state,
     * and showing both would be the same offer twice. They appear between questions, when the
     * composer is empty and the member has not just started typing, and are dismissed per set of
     * jobs, so moving to a record that supports different work offers the new ones rather than
     * staying silent because an earlier set was waved away.
     */
    const jobsKey = `${activeSession?.id ?? 'new'}|${jobs.map((job) => job.id).join(',')}`;
    const [dismissedJobsKey, setDismissedJobsKey] = useState<string | null>(null);
    const offeringJobs = jobs.length > 0
        && visibleMessages.length > 0
        && composer.trim().length === 0
        && loadState === 'ready'
        && unavailable === null
        && !working
        && dismissedJobsKey !== jobsKey;
    const sendAvailable = loadState === 'ready'
        && !contextOverflow
        && !fileOperationPending
        && !scope.blocked
        && !busy
        && unavailable === null;
    const canSend = askConnexSendable({
        available: sendAvailable,
        composer,
        composerTooLong: contentTooLong,
    });
    const contextGroupRef = useRef<HTMLDivElement>(null);
    const [pendingScope, setPendingScope] = useState<{ content?: string } | null>(null);
    const scopeKey = requestScope.identity;
    /**
     * A held request keeps its notice until the member acts on it, not until its breadth changes.
     *
     * The breadth is still being measured while the notice is up, and the answer may well be that it
     * needed no review — dropping the notice the moment that lands would take the member's own Send
     * with it. What does close it is there being nothing left to announce: a request that no longer
     * carries records or declares filters has no breadth to state, so the notice goes and Send is
     * the member's to press again.
     */
    const asking = pendingScope !== null
        && (requestScope.records !== null || requestScope.declared !== null);

    /**
     * Lands the member in the composer after a contextual entry point wrote a job into it.
     *
     * The job arrives as ordinary text they may want to change before sending, so focus goes to the
     * end of it rather than to whatever control opened the panel. Exactly once per entry point, and
     * only once the panel is actually on screen: an entry point pressed from a record opens the panel
     * and writes the job in the same moment, while opening the panel by itself later must not take
     * focus — on a phone that would raise the keyboard over the conversation. Consuming the request
     * is what makes that hold: the mark belongs to the caller, not to this surface, which a phone
     * unmounts with the panel.
     */
    useEffect(() => {
        if (!askConnexPromptFocusPending(open, promptRequest)) return;
        onPromptConsumed();
        composerRef.current?.focus();
    }, [onPromptConsumed, open, promptRequest]);

    /**
     * Offers one job to the composer, wherever it was offered from.
     *
     * The empty state and the strip above the composer hand a job over on the same terms a record's
     * entry point does: a half-written question is the member's work, so an offer joins it rather
     * than replacing it, and focus lands after it so the next thing they type continues the message.
     */
    const offerJob = (job: AskConnexJobOffer) => {
        onComposerChange(appendAskConnexPrompt(composer, job.prompt));
        composerRef.current?.focus();
    };

    /**
     * Asks for one question to be sent, announcing its breadth first whenever it has one.
     *
     * Every broad request is announced, including the second one against filters that were already
     * agreed to once: agreement is given to a question, not to a set of filters left in the form, and
     * a notice that only ever appears once would let every later question inherit a review nobody
     * performed on it. Settling the breadth check comes first, so what the notice states is measured
     * against the question actually about to go out rather than an earlier one.
     */
    const requestSend = (content?: string) => {
        if (!askConnexSendable({
            available: sendAvailable,
            composer,
            suggestion: content,
            composerTooLong: contentTooLong,
        })) return;
        scope.onSettle(content);
        if (scopeKey !== null) {
            setPendingScope({ content });
            return;
        }
        setPendingScope(null);
        onSend(content);
    };

    /**
     * Hands a stopped answer back to the member as an ordinary question they can read and edit.
     *
     * The message is appended through the composer rather than sent, because continuing is a new
     * question and Ask Connex does not ask questions on the member's behalf. Appending also keeps
     * anything already typed instead of replacing it.
     */
    const continueFromPartial = () => {
        if (continuePrompt === null) return;
        composerRef.current?.appendParagraph(continuePrompt);
        composerRef.current?.focus();
    };

    const carryingContext = hasAskConnexContextInputs({
        implicitContext,
        pinnedContext,
        selectionContext,
        unsupportedPageContext,
        attachments,
        fileAttachments,
        scopeChips: scope.chips,
    });

    /**
     * Returns the member to the inputs that decide breadth, and makes the move visible.
     *
     * Where that is depends on what made the request broad: carried records are removed in the
     * context strip, and a question that was broad on its own words is narrowed in the composer, so
     * a request with no carried records lands in the composer rather than on an empty strip. The
     * strip takes focus as a group and shows a ring while it holds it, because focus arrives here
     * from a click and nothing else would tell the member the press did anything.
     */
    const focusBreadthInputs = () => {
        const group = carryingContext ? contextGroupRef.current : null;
        if (group !== null) {
            group.focus();
            return;
        }
        composerRef.current?.focus();
    };

    /**
     * Puts the held request down and hands the member back the inputs that decide breadth.
     *
     * Nothing is sent and nothing is agreed to: the next send announces whatever breadth the request
     * has by then, so narrowing it here is a change of mind rather than a step on the way out.
     */
    const narrowScope = () => {
        setPendingScope(null);
        focusBreadthInputs();
    };

    return (
        <div className={cn(
            'flex h-full min-h-0 flex-col text-popover-foreground',
            workspace ? 'bg-background' : 'bg-popover',
        )}>
            <SurfaceHeader
                workspace={workspace}
                resizable={resizable}
                activeSession={activeSession}
                participants={participants}
                activeState={activeState}
                contextCount={contextCount}
                width={width}
                labels={labels}
                closeButton={closeButton}
                onWidthChange={onWidthChange}
                onOpenWorkspace={onOpenWorkspace}
                sessionMenu={(
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
                )}
            />

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
                                            <div
                                                className={cn(
                                                    'flex w-full flex-col gap-1.5',
                                                    workspace ? 'max-w-xl' : 'max-w-sm',
                                                )}
                                            >
                                                {jobs.map((job) => (
                                                    <Button
                                                        key={job.id}
                                                        type="button"
                                                        variant="ghost"
                                                        className="h-auto justify-start whitespace-normal bg-muted/60 py-2 text-left"
                                                        onClick={() => offerJob(job)}
                                                    >
                                                        {job.label}
                                                    </Button>
                                                ))}
                                                <p className="pt-3 text-xs leading-relaxed text-muted-foreground">
                                                    {labels.disclosureCreation}
                                                </p>
                                                {workspace ? (
                                                    <>
                                                        <Separator className="my-4" />
                                                        <AskConnexCommandCenter />
                                                    </>
                                                ) : null}
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
                                            review={toolReview}
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
                                                                review={toolReview}
                                                                suggestionsDisabled={!sendAvailable}
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
                                                                review={toolReview}
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
                            {loadState === 'ready' && liveTodoPlan.length > 0 ? (
                                <MessageScrollerItem
                                    messageId={`todos:${turn.turnId ?? 'pending'}`}
                                >
                                    <div className="pl-10">
                                        <TodoPlan
                                            todos={liveTodoPlan}
                                            label={labels.todoPlan}
                                            statusLabels={labels.todoStatus}
                                            live
                                        />
                                    </div>
                                </MessageScrollerItem>
                            ) : null}
                            {loadState === 'ready' && liveNarration.length > 0 ? (
                                <MessageScrollerItem
                                    messageId={`narration:${turn.turnId ?? 'pending'}`}
                                    className="px-4 pt-5 last:pb-5"
                                >
                                    <div className="pl-10">
                                        <NarrationTrail
                                            segments={liveNarration}
                                            label={labels.narrationTrail}
                                        />
                                    </div>
                                </MessageScrollerItem>
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
                                        hasPartial={hasPartialAnswer}
                                        thinking={thinking}
                                        labels={labels}
                                        onCancel={onCancelTurn}
                                        onRetry={onRetryTurn}
                                        onContinueFromPartial={continueFromPartial}
                                        onNarrowScope={narrowScope}
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
                    {offeringJobs ? (
                        <JobSuggestions
                            jobs={jobs}
                            label={labels.suggestions}
                            dismissLabel={labels.dismissSuggestions}
                            onUse={offerJob}
                            onDismiss={() => setDismissedJobsKey(jobsKey)}
                        />
                    ) : null}
                    <AskConnexContextStrip
                        groupRef={contextGroupRef}
                        implicitContext={implicitContext}
                        pinnedContext={pinnedContext}
                        pageContextPinned={pageContextPinned}
                        selectionContext={selectionContext}
                        unsupportedPageContext={unsupportedPageContext}
                        attachments={attachments}
                        fileAttachments={fileAttachments}
                        scopeChips={scope.chips}
                        scopeRefusal={scope.refusal}
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
                        onEditScope={() => scope.onEditorOpenChange(true)}
                        onReset={onResetContext}
                    />
                    {asking ? (
                        <AskConnexScopeNotice
                            scope={requestScope}
                            labels={labels}
                            onConfirm={() => {
                                const content = pendingScope?.content;
                                setPendingScope(null);
                                onSend(content);
                            }}
                            onEdit={() => {
                                setPendingScope(null);
                                focusBreadthInputs();
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
                            handleRef={composerRef}
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
                            <Button
                                type="button"
                                variant={scope.filterCount > 0 ? 'secondary' : 'ghost'}
                                size="inline"
                                className="shrink-0 font-normal"
                                disabled={busy || loadState !== 'ready' || unavailable !== null}
                                onClick={() => scope.onEditorOpenChange(true)}
                            >
                                <FunnelIcon className="size-3.5" />
                                {scope.filterCount > 0
                                    ? labels.scopeFiltersSet(scope.filterCount)
                                    : labels.scopeFilters}
                            </Button>
                            <div className="min-w-0 text-xs text-muted-foreground">
                                {contentTooLong ? (
                                    <p role="alert" className="text-destructive">{labels.tooLong}</p>
                                ) : scope.blocked && scope.problem !== null ? (
                                    <p role="alert" className="truncate text-destructive">{scope.problem}</p>
                                ) : (
                                    <p className="hidden sm:block">{labels.composerHint}</p>
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
                    <AskConnexScopeEditor
                        open={scope.editorOpen}
                        draft={scope.draft}
                        preview={scope.preview}
                        skills={scope.skills}
                        onOpenChange={scope.onEditorOpenChange}
                        onDraftChange={scope.onDraftChange}
                    />
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
    meta,
    current,
    disabled,
    leading,
    trailing,
    onSelect,
}: {
    title: string;
    meta?: ReactNode;
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
                'flex min-h-11 w-full items-start gap-2 rounded-lg px-3 py-2 text-left text-sm outline-none hover:bg-muted focus-visible:ring-2 focus-visible:ring-ring disabled:opacity-50',
                current && 'bg-muted font-medium text-foreground',
            )}
        >
            {leading ? <span className="mt-0.5 shrink-0">{leading}</span> : null}
            <span className="min-w-0 flex-1">
                <span className="block truncate">{title}</span>
                {meta ? (
                    <span className="mt-0.5 flex min-w-0 items-center gap-1.5 text-xs font-normal text-muted-foreground">
                        {meta}
                    </span>
                ) : null}
            </span>
            {trailing ? <span className="mt-0.5 shrink-0">{trailing}</span> : null}
        </button>
    );
}

/**
 * The workspace's session rail.
 *
 * Chats are banded by when they were last active rather than listed flat, because "the one I was in
 * this morning" is how a member looks for a chat and a title alone does not answer it. Each row
 * says when it was last active and whether other members can see it; the chat currently open also
 * says what it is doing, which is the one row whose live state this client actually follows.
 *
 * Bands the list cannot support honestly are absent rather than approximated: the session list
 * carries no running, failed, or pending-approval flag for any chat but the open one, and a band
 * derived from data the client does not have would be right only by accident.
 */
function WorkspaceSessionRail({
    sessions,
    invitations,
    activeSession,
    activeState,
    working,
    now,
    labels,
    onSelectSession,
    onJoinInvitation,
    onNewChat,
}: {
    sessions: AiChatSession[];
    invitations: AiChatSession[];
    activeSession: AiChatSession | null;
    activeState: AskConnexActiveState;
    working: boolean;
    now: number;
    labels: AskConnexDrawerLabels;
    onSelectSession: (session: AiChatSession) => void;
    onJoinInvitation: (session: AiChatSession) => void;
    onNewChat: () => void;
}) {
    const [query, setQuery] = useState('');
    const filtered = useMemo(() => filterAskConnexSessions(sessions, query), [query, sessions]);
    const filteredInvitations = useMemo(
        () => filterAskConnexSessions(invitations, query),
        [invitations, query],
    );
    const groups = useMemo(
        () => groupAskConnexSessions(filtered, filteredInvitations, now),
        [filtered, filteredInvitations, now],
    );
    const stateLabel = activeState === 'running'
        ? labels.stateRunning
        : activeState === 'awaitingApproval'
            ? labels.stateAwaitingApproval
            : activeState === 'failed'
                ? labels.stateFailed
                : null;

    return (
        <aside className="hidden min-h-0 w-72 shrink-0 flex-col border-r border-border bg-muted/30 md:flex">
            <div className="flex items-center gap-2 border-b border-border px-4 py-3">
                <div className="min-w-0 flex-1">
                    <p className="text-sm font-semibold text-foreground">{labels.title}</p>
                    <p className="truncate text-xs text-muted-foreground">{labels.sessionRail}</p>
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
            <nav aria-label={labels.sessionRail} className="min-h-0 flex-1 overflow-y-auto px-2 pb-3">
                {groups.length === 0 ? (
                    <p className="px-3 py-8 text-center text-xs text-muted-foreground">
                        {query.trim().length > 0 ? labels.noMatchingSessions : labels.noRecentSessions}
                    </p>
                ) : groups.map((group) => (
                    <section key={group.key} aria-labelledby={`ask-connex-rail-${group.key}`}>
                        <h2
                            id={`ask-connex-rail-${group.key}`}
                            className="px-3 pt-3 pb-1 text-xs font-medium text-muted-foreground"
                        >
                            {labels.sessionGroup(group.key)}
                        </h2>
                        {group.sessions.map((session) => {
                            const invitation = group.key === 'invitations';
                            const current = !invitation && activeSession?.id === session.id;
                            const lastActive = session.lastMessageAt ?? session.updatedAt;
                            const activity = askConnexSessionActivity(session);
                            return (
                                <SessionRailRow
                                    key={`${group.key}:${session.id}`}
                                    title={session.title}
                                    current={current}
                                    disabled={working}
                                    leading={invitation
                                        ? <UserPlusIcon className="size-4 text-muted-foreground" />
                                        : null}
                                    meta={(
                                        <>
                                            {session.visibility === 'shared' ? (
                                                <UserGroupIcon aria-hidden className="size-3.5 shrink-0" />
                                            ) : null}
                                            {activity > 0 ? (
                                                <time
                                                    dateTime={new Date(activity).toISOString()}
                                                    className="truncate"
                                                >
                                                    {labels.sessionActivity(labels.relativeTime(lastActive))}
                                                </time>
                                            ) : null}
                                            {current && stateLabel !== null ? (
                                                <span className="truncate">{stateLabel}</span>
                                            ) : null}
                                        </>
                                    )}
                                    trailing={invitation
                                        ? <span className="text-xs text-muted-foreground">{labels.join}</span>
                                        : null}
                                    onSelect={() => invitation
                                        ? onJoinInvitation(session)
                                        : onSelectSession(session)}
                                />
                            );
                        })}
                    </section>
                ))}
            </nav>
            <p className="border-t border-border px-4 py-3 text-xs leading-relaxed text-muted-foreground">
                {labels.disclosureList}
            </p>
        </aside>
    );
}

/**
 * Responsive Ask Connex session, transcript, context, and composer surface.
 *
 * The desktop panel animates its transform and nothing else. Its width is the width the member
 * chose, applied as a plain style: a panel that reached a new width over a spring would relayout its
 * whole transcript on every frame of that spring, and it could not be kept in step with the shell
 * column the page reflows into without the shell paying the same cost again. Resizing is therefore
 * a discrete change on both surfaces at once; opening and closing stays animated, and there the
 * panel only translates.
 */
export default function AskConnexDrawer(props: AskConnexDrawerProps) {
    const {
        open,
        instantOpen,
        isMobile,
        showTab,
        workspace,
        width,
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
        requestScope: props.requestScope,
        scope: props.scope,
        attachments: props.attachments,
        fileAttachments: props.fileAttachments,
        canAttachFiles: props.canAttachFiles,
        canRemoveFiles: props.canRemoveFiles,
        contextOverflow: props.contextOverflow,
        contentTooLong: props.contentTooLong,
        working: props.working,
        turn: props.turn,
        thinking: props.thinking,
        narration: props.narration,
        todos: props.todos,
        streamStore: props.streamStore,
        streaming: props.streaming,
        cancelling: props.cancelling,
        toolCalls: props.toolCalls,
        actionableToolCallIds: props.actionableToolCallIds,
        canRetryTurn: props.canRetryTurn,
        continuePrompt: props.continuePrompt,
        width: props.width,
        activeState: props.activeState,
        contextCount: props.contextCount,
        now: props.now,
        unavailable: props.unavailable,
        jobs: props.jobs,
        promptRequest: props.promptRequest,
        onPromptConsumed: props.onPromptConsumed,
        labels: props.labels,
        workspace: props.workspace,
        closeButton: null,
        resizable: false,
        onWidthChange: props.onWidthChange,
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
        onToolActions: props.onToolActions,
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
                style={{ width: askConnexWidthLength(width) }}
                animate={{ transform: open ? 'translateX(0%)' : 'translateX(100%)' }}
                transition={instantOpen || reduceMotion ? instant : springSmooth}
                onAnimationComplete={() => onOpenChangeComplete(open)}
                className={cn(
                    'absolute inset-y-0 right-0 border-l border-border bg-popover',
                    open ? 'pointer-events-auto' : 'pointer-events-none',
                )}
            >
                <ConversationSurface
                    {...surfaceProps}
                    open={open}
                    resizable
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
                activeState={props.activeState}
                working={props.working}
                now={props.now}
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
