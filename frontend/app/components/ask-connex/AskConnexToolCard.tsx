'use client';

import { useEffect, useId, useRef, useState, type ComponentType, type SVGProps } from 'react';
import {
    ArrowPathIcon,
    CheckCircleIcon,
    ClockIcon,
    ExclamationTriangleIcon,
    InformationCircleIcon,
    LinkIcon,
    LockClosedIcon,
    MinusCircleIcon,
    NoSymbolIcon,
    PlusCircleIcon,
    XCircleIcon,
} from '@heroicons/react/24/outline';
import Link from 'next/link';

import { ANSWER_ROW_PLACEHOLDER } from '@/app/components/ask-connex/answerDocument';
import { useLiveNow } from '@/app/hooks/useNow';
import {
    askConnexCreatedRecordHref,
    askConnexToolCardAffordances,
    askConnexToolCardStatus,
    askConnexToolOutcomeSummary,
    askConnexToolRequestSummary,
    askConnexToolTargetHref,
    askConnexUndoWindow,
    isAskConnexOutcomeField,
    type AskConnexOutcomeField,
    type AskConnexToolAction,
    type AskConnexToolCardFailure,
    type AskConnexToolCardState,
    type AskConnexToolSummaryLabels,
} from '@/app/lib/askConnex';
import type {
    AiAssistantCreatedRecordKind,
    AiAssistantToolCallChange,
    AiAssistantToolCallChangeState,
} from '@/app/lib/types';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';

type StatusPresentation = {
    icon: ComponentType<SVGProps<SVGSVGElement>>;
    label: string;
    detail: string;
    className: string;
    badgeVariant: 'default' | 'secondary' | 'destructive' | 'outline';
};

/** Localized names for the record fields an assistant proposal can rewrite. */
export type AskConnexChangeFieldLabels = {
    owner: string;
    stage: string;
};

/** Localized names for the values a completed assistant action reports. */
export type AskConnexOutcomeFieldLabels = Record<AskConnexOutcomeField, string> & {
    /** What to call a field this client has no word for yet, rather than showing its identifier. */
    other: string;
};

/** Localized names for a value the record no longer resolves to anything this workspace can show. */
export type AskConnexUnresolvedValueLabels = {
    owner: string;
    stage: string;
};

/** Localized copy consumed by the presentational assistant tool-call card. */
export type AskConnexToolCardLabels = {
    actionFailed: string;
    apply: string;
    applyAria: (target: string) => string;
    applying: string;
    changeField: AskConnexChangeFieldLabels;
    changeNotSet: string;
    /** What the record currently holds, when this workspace can no longer name who or what it is. */
    changeCurrentUnresolved: AskConnexUnresolvedValueLabels;
    /** What the proposal asked for, when that value no longer exists in this workspace. */
    changeProposedUnresolved: string;
    changeState: Record<Exclude<AiAssistantToolCallChangeState, 'ready'>, string>;
    diffAfter: string;
    diffBefore: string;
    discard: string;
    discardAria: (target: string) => string;
    discarding: string;
    editOnRecord: string;
    editOnRecordAria: (target: string) => string;
    executedDetail: string;
    executedStatus: string;
    expiredDetail: string;
    expiredStatus: string;
    failedDetail: string;
    failedStatus: string;
    openCreatedRecord: (kind: AiAssistantCreatedRecordKind) => string;
    openCreatedRecordAria: (kind: AiAssistantCreatedRecordKind) => string;
    outcome: string;
    outcomeField: AskConnexOutcomeFieldLabels;
    /** States one written value in the reader's own locale, by the kind of value the field holds. */
    outcomeValue: (field: string, value: string) => string;
    pendingDetail: string;
    pendingStatus: string;
    proposalChanged: string;
    proposalPermissionLost: string;
    proposalUnavailable: string;
    proposedChange: string;
    recordLink: (target: string) => string;
    rejectedDetail: string;
    rejectedStatus: string;
    restrictedTarget: string;
    undo: string;
    undoAria: (target: string) => string;
    undoConflict: string;
    undoneDetail: string;
    undoneStatus: string;
    undoing: string;
    /** The real deadline, and how much of the window is left, from the server's own instant. */
    undoWindow: (deadline: string, remaining: string) => string;
    summaries: AskConnexToolSummaryLabels;
};

type AskConnexToolCardProps = {
    card: AskConnexToolCardState;
    labels: AskConnexToolCardLabels;
    actionsDisabled: boolean;
    onAction: (toolCallId: number, action: AskConnexToolAction) => void;
    /** Renders the remaining undo window and result timestamps in the reader's locale. */
    formatDeadline: (instant: string) => string;
    formatRemaining: (instant: string) => string;
};

function statusPresentation(
    status: ReturnType<typeof askConnexToolCardStatus>,
    labels: AskConnexToolCardLabels,
): StatusPresentation {
    if (status === 'executed') {
        return {
            icon: CheckCircleIcon,
            label: labels.executedStatus,
            detail: labels.executedDetail,
            className: 'border-primary/25 bg-primary/5',
            badgeVariant: 'default',
        };
    }
    if (status === 'rejected') {
        return {
            icon: NoSymbolIcon,
            label: labels.rejectedStatus,
            detail: labels.rejectedDetail,
            className: 'border-border bg-muted/40',
            badgeVariant: 'secondary',
        };
    }
    if (status === 'undone') {
        return {
            icon: ArrowPathIcon,
            label: labels.undoneStatus,
            detail: labels.undoneDetail,
            className: 'border-border bg-background',
            badgeVariant: 'outline',
        };
    }
    if (status === 'failed') {
        return {
            icon: XCircleIcon,
            label: labels.failedStatus,
            detail: labels.failedDetail,
            className: 'border-destructive/30 bg-destructive/5',
            badgeVariant: 'destructive',
        };
    }
    if (status === 'expired') {
        return {
            icon: ClockIcon,
            label: labels.expiredStatus,
            detail: labels.expiredDetail,
            className: 'border-border bg-muted/20',
            badgeVariant: 'outline',
        };
    }
    return {
        icon: ClockIcon,
        label: labels.pendingStatus,
        detail: labels.pendingDetail,
        className: 'border-primary/25 bg-background',
        badgeVariant: 'outline',
    };
}

/** The one sentence a rolled-back action leaves behind, wherever that action was pressed. */
export function askConnexFailureMessage(
    failure: AskConnexToolCardFailure,
    labels: AskConnexToolCardLabels,
): string {
    if (failure === 'proposalChanged') return labels.proposalChanged;
    if (failure === 'proposalPermissionLost') return labels.proposalPermissionLost;
    if (failure === 'proposalUnavailable') return labels.proposalUnavailable;
    if (failure === 'undoConflict') return labels.undoConflict;
    return labels.actionFailed;
}

const CHANGE_STATE_ICON: Record<
    Exclude<AiAssistantToolCallChangeState, 'ready'>,
    ComponentType<SVGProps<SVGSVGElement>>
> = {
    unchanged: InformationCircleIcon,
    recordChanged: ClockIcon,
    permissionLost: LockClosedIcon,
    unresolved: ExclamationTriangleIcon,
};

function NotSetValue({ labels }: { labels: AskConnexToolCardLabels }) {
    return (
        <>
            <span aria-hidden>{ANSWER_ROW_PLACEHOLDER}</span>
            <span className="sr-only">{labels.changeNotSet}</span>
        </>
    );
}

/**
 * One field's exact before and after values.
 *
 * The same visual sentence the answer document's change rows use — a struck-through current value
 * above the proposed one, each named in words rather than by colour — so a member who has read a
 * change in an answer reads this one without learning a second grammar. A value the record does
 * not have is written as the shared placeholder and read aloud as "not set", which is what makes a
 * first assignment and a removal both legible instead of appearing as a missing row.
 *
 * Two values are not "not set" and must never borrow its placeholder. A record whose owner has
 * left this workspace holds a real value nobody here can name, and a proposal whose value no
 * longer exists asked for something that is gone — writing either as an empty field would read as
 * a legitimate clearing proposal and contradict the very notice printed underneath it.
 */
export function AskConnexChangeRow({
    change,
    labels,
}: {
    change: AiAssistantToolCallChange;
    labels: AskConnexToolCardLabels;
}) {
    const fieldLabel = labels.changeField[change.field];
    return (
        <div className="space-y-1.5 rounded-xl px-3 py-2.5 ring-1 ring-border">
            <span className="block break-words text-xs font-medium text-muted-foreground">
                {fieldLabel}
            </span>
            <div className="grid grid-cols-[auto_auto_minmax(0,1fr)] items-start gap-x-2 gap-y-1">
                <MinusCircleIcon aria-hidden className="mt-0.5 size-3.5 shrink-0 text-muted-foreground" />
                <span className="text-xs text-muted-foreground">{labels.diffBefore}</span>
                <span className="break-words text-sm text-muted-foreground line-through decoration-muted-foreground/70">
                    {change.currentValueUnresolved
                        ? labels.changeCurrentUnresolved[change.field]
                        : change.currentValue ?? <NotSetValue labels={labels} />}
                </span>
                <PlusCircleIcon aria-hidden className="mt-0.5 size-3.5 shrink-0 text-foreground" />
                <span className="text-xs text-muted-foreground">{labels.diffAfter}</span>
                <span className="break-words text-sm font-medium text-foreground">
                    {change.state === 'unresolved'
                        ? labels.changeProposedUnresolved
                        : change.proposedValue ?? <NotSetValue labels={labels} />}
                </span>
            </div>
        </div>
    );
}

/**
 * Why a reviewed change cannot be applied as it stands.
 *
 * A record written since the proposal was made reads here as a change that has to be asked for
 * again, not as a caution to read before applying, and its card carries no apply control. That is
 * the deliberate consequence of the server's fail-closed staleness rule: approval refuses any
 * proposal whose target moved, permanently, so a proposal reviewed against a record that has since
 * moved is re-asked rather than re-baselined against values nobody reviewed.
 */
export function AskConnexChangeNotice({
    state,
    labels,
}: {
    state: AiAssistantToolCallChangeState;
    labels: AskConnexToolCardLabels;
}) {
    if (state === 'ready') return null;
    const NoticeIcon = CHANGE_STATE_ICON[state];
    const blocking = state === 'permissionLost' || state === 'unresolved';
    return (
        <p className={cn(
            'flex items-start gap-2 text-xs leading-relaxed',
            blocking ? 'text-destructive' : 'text-muted-foreground',
        )}>
            <NoticeIcon aria-hidden className="mt-0.5 size-4 shrink-0" />
            <span>{labels.changeState[state]}</span>
        </p>
    );
}

/** Accessible semantic card for proposed, executed, and terminal assistant write actions. */
export default function AskConnexToolCard({
    card,
    labels,
    actionsDisabled,
    onAction,
    formatDeadline,
    formatRemaining,
}: AskConnexToolCardProps) {
    const now = useLiveNow();
    const titleId = useId();
    const articleRef = useRef<HTMLElement>(null);
    const failureRef = useRef<HTMLDivElement>(null);
    const settlingAction = useRef<AskConnexToolAction | null>(null);
    const pressedWithFocus = useRef(false);
    const [retiredUndoWindow, setRetiredUndoWindow] = useState<string | null>(null);
    const undoWindowRetired = card.undoExpiresAt !== null
        && retiredUndoWindow === card.undoExpiresAt;
    const effectiveNow = undoWindowRetired ? Number.POSITIVE_INFINITY : now;
    const status = askConnexToolCardStatus(card, effectiveNow);
    const presentation = statusPresentation(status, labels);
    const affordances = askConnexToolCardAffordances(card, effectiveNow);
    const StatusIcon = presentation.icon;
    const targetHref = askConnexToolTargetHref(card.target);
    const targetName = card.target.label ?? labels.restrictedTarget;
    const requestSummary = askConnexToolRequestSummary(card, labels.summaries);
    const outcomeSummary = askConnexToolOutcomeSummary(card, labels.summaries);
    const undoWindow = askConnexUndoWindow(card, effectiveNow);
    const busy = card.pendingAction !== null;
    const proposal = status === 'proposed' ? card.change : null;
    const resultValues = status === 'executed' || status === 'expired' ? card.outcomeValues : [];
    const createdRecordHref = status === 'executed' || status === 'expired'
        ? askConnexCreatedRecordHref(card.createdRecord)
        : null;

    /**
     * Presses one of this card's own controls, remembering whether it was holding focus.
     *
     * The control is about to be replaced by the card's next state, and whether that should move
     * the reader depends entirely on where the reader currently is. A member who clicked with a
     * mouse and moved straight to the composer is mid-sentence; pulling them back to a card that
     * finished in the background would take the keystrokes with it.
     */
    function press(action: AskConnexToolAction) {
        const article = articleRef.current;
        pressedWithFocus.current = article !== null
            && document.activeElement !== null
            && article.contains(document.activeElement);
        onAction(card.id, action);
    }

    /**
     * Keeps the reader where they were when their own decision lands.
     *
     * Applying, discarding, or undoing removes the control that was pressed, and a focused control
     * that disappears drops focus to the document — a keyboard reader is returned to the top of the
     * page and a screen-reader user loses the card they were deciding. Focus goes to whatever now
     * answers them: the failure alert when the action was refused, and otherwise the card itself,
     * which is named by its own heading.
     *
     * Three conditions all have to hold, and each of them is somebody's real session. The card must
     * have been carrying a decision, so a refresh that clears state the reader never started does
     * not move them. The control must have held focus when it was pressed, so a mouse press does
     * not yank a keyboard reader elsewhere on the page. And focus must actually have been dropped —
     * left on the document, or on the control that has since been taken out of the tree — so a
     * member who has moved to the composer in the meantime keeps their cursor and their keystrokes.
     */
    useEffect(() => {
        const settled = settlingAction.current !== null && card.pendingAction === null;
        settlingAction.current = card.pendingAction;
        if (!settled) return;
        const restore = pressedWithFocus.current;
        pressedWithFocus.current = false;
        if (!restore) return;
        const active = document.activeElement;
        const dropped = active === null
            || active === document.body
            || !document.body.contains(active);
        if (!dropped) return;
        const destination = card.failure === null ? articleRef.current : failureRef.current;
        destination?.focus();
    }, [card.failure, card.pendingAction]);

    useEffect(() => {
        if (!card.undoAvailable || card.undoExpiresAt === null) return;
        const remaining = Date.parse(card.undoExpiresAt) - Date.now();
        if (!Number.isFinite(remaining)) return;
        if (remaining <= 0) {
            const frame = window.requestAnimationFrame(() => {
                setRetiredUndoWindow(card.undoExpiresAt);
            });
            return () => window.cancelAnimationFrame(frame);
        }
        const timer = window.setTimeout(() => {
            setRetiredUndoWindow(card.undoExpiresAt);
        }, remaining);
        return () => window.clearTimeout(timer);
    }, [card.undoAvailable, card.undoExpiresAt]);

    return (
        <article
            ref={articleRef}
            tabIndex={-1}
            aria-busy={busy}
            aria-labelledby={titleId}
            className={cn(
                'rounded-xl border p-4 outline-none focus-visible:ring-2 focus-visible:ring-ring',
                presentation.className,
            )}
        >
            <div className="flex items-start gap-3">
                <span className="mt-0.5 grid size-8 shrink-0 place-items-center rounded-full bg-background text-foreground ring-1 ring-border">
                    <StatusIcon aria-hidden className="size-4" />
                </span>
                <div className="min-w-0 flex-1 space-y-3">
                    <div aria-live="polite" className="space-y-1">
                        <div className="flex flex-wrap items-start gap-2">
                            <h3
                                id={titleId}
                                className="min-w-0 flex-1 break-words text-sm font-semibold text-foreground"
                            >
                                {requestSummary}
                            </h3>
                            <Badge variant={presentation.badgeVariant}>{presentation.label}</Badge>
                        </div>
                        <p className="text-xs leading-relaxed text-muted-foreground">
                            {presentation.detail}
                        </p>
                    </div>

                    <div className="space-y-1">
                        {createdRecordHref !== null && card.createdRecord !== null ? (
                            <Link
                                href={createdRecordHref}
                                aria-label={labels.openCreatedRecordAria(card.createdRecord.kind)}
                                className="inline-flex max-w-full items-center gap-1.5 rounded-md text-sm font-medium text-foreground outline-none hover:text-primary focus-visible:ring-2 focus-visible:ring-ring"
                            >
                                <LinkIcon aria-hidden className="size-4 shrink-0" />
                                <span className="truncate">
                                    {labels.openCreatedRecord(card.createdRecord.kind)}
                                </span>
                            </Link>
                        ) : null}
                        {targetHref !== null && card.target.label !== null ? (
                            <Link
                                href={targetHref}
                                aria-label={labels.recordLink(card.target.label)}
                                className={cn(
                                    'inline-flex max-w-full items-center gap-1.5 rounded-md outline-none hover:text-primary focus-visible:ring-2 focus-visible:ring-ring',
                                    createdRecordHref === null
                                        ? 'text-sm font-medium text-foreground'
                                        : 'text-xs text-muted-foreground',
                                )}
                            >
                                {createdRecordHref === null ? (
                                    <LinkIcon aria-hidden className="size-4 shrink-0" />
                                ) : null}
                                <span className="truncate">{card.target.label}</span>
                            </Link>
                        ) : (
                            <span className="inline-flex items-center gap-1.5 text-sm text-muted-foreground">
                                <NoSymbolIcon aria-hidden className="size-4 shrink-0" />
                                {labels.restrictedTarget}
                            </span>
                        )}
                    </div>

                    {proposal !== null ? (
                        <div className="space-y-2">
                            <p className="text-xs text-muted-foreground">{labels.proposedChange}</p>
                            <AskConnexChangeRow change={proposal} labels={labels} />
                            <AskConnexChangeNotice state={proposal.state} labels={labels} />
                        </div>
                    ) : null}

                    {outcomeSummary !== null ? (
                        <div className="space-y-1.5 text-sm">
                            <p className="text-xs text-muted-foreground">{labels.outcome}</p>
                            <p className="break-words text-foreground">{outcomeSummary}</p>
                            {resultValues.length > 0 ? (
                                <dl className="grid grid-cols-1 gap-x-3 gap-y-0.5 sm:grid-cols-[minmax(0,7rem)_minmax(0,1fr)]">
                                    {resultValues.map((value) => (
                                        <div key={value.field} className="contents">
                                            <dt className="break-words text-xs text-muted-foreground">
                                                {isAskConnexOutcomeField(value.field)
                                                    ? labels.outcomeField[value.field]
                                                    : labels.outcomeField.other}
                                            </dt>
                                            <dd className="min-w-0 break-words text-sm text-foreground">
                                                {labels.outcomeValue(value.field, value.value)}
                                            </dd>
                                        </div>
                                    ))}
                                </dl>
                            ) : null}
                        </div>
                    ) : null}

                    {undoWindow.state === 'open' ? (
                        <p className="flex items-start gap-2 text-xs leading-relaxed text-muted-foreground">
                            <ClockIcon aria-hidden className="mt-0.5 size-4 shrink-0" />
                            <span>
                                {labels.undoWindow(
                                    formatDeadline(undoWindow.expiresAt),
                                    formatRemaining(undoWindow.expiresAt),
                                )}
                            </span>
                        </p>
                    ) : null}

                    {card.failure !== null ? (
                        <div
                            ref={failureRef}
                            role="alert"
                            tabIndex={-1}
                            className="flex items-start gap-2 rounded-md text-xs leading-relaxed text-destructive outline-none focus-visible:ring-2 focus-visible:ring-ring"
                        >
                            <ExclamationTriangleIcon aria-hidden className="mt-0.5 size-4 shrink-0" />
                            <span>{askConnexFailureMessage(card.failure, labels)}</span>
                        </div>
                    ) : null}

                    {affordances.length > 0 || (proposal !== null && targetHref !== null) ? (
                        <div className="flex flex-wrap justify-end gap-2 pt-1">
                            {affordances.includes('reject') ? (
                                <Button
                                    type="button"
                                    variant="ghost"
                                    size="dialog"
                                    aria-label={labels.discardAria(targetName)}
                                    disabled={busy || actionsDisabled}
                                    onClick={() => press('reject')}
                                >
                                    {card.pendingAction === 'reject' ? labels.discarding : labels.discard}
                                </Button>
                            ) : null}
                            {proposal !== null && targetHref !== null ? (
                                <Button asChild variant="outline" size="dialog">
                                    <Link
                                        href={targetHref}
                                        aria-label={labels.editOnRecordAria(targetName)}
                                    >
                                        {labels.editOnRecord}
                                    </Link>
                                </Button>
                            ) : null}
                            {affordances.includes('approve') ? (
                                <Button
                                    type="button"
                                    size="dialog"
                                    aria-label={labels.applyAria(targetName)}
                                    disabled={busy || actionsDisabled}
                                    onClick={() => press('approve')}
                                >
                                    {card.pendingAction === 'approve' ? labels.applying : labels.apply}
                                </Button>
                            ) : null}
                            {affordances.includes('undo') ? (
                                <Button
                                    type="button"
                                    variant="outline"
                                    size="dialog"
                                    aria-label={labels.undoAria(targetName)}
                                    disabled={busy || actionsDisabled}
                                    onClick={() => press('undo')}
                                >
                                    {card.pendingAction === 'undo' ? labels.undoing : labels.undo}
                                </Button>
                            ) : null}
                        </div>
                    ) : null}
                </div>
            </div>
        </article>
    );
}
