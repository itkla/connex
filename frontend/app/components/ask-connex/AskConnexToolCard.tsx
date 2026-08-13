'use client';

import { useEffect, useState, type ComponentType, type SVGProps } from 'react';
import {
    ArrowPathIcon,
    ArrowRightIcon,
    CheckCircleIcon,
    ClockIcon,
    ExclamationTriangleIcon,
    LinkIcon,
    NoSymbolIcon,
    XCircleIcon,
} from '@heroicons/react/24/outline';
import Link from 'next/link';

import { useLiveNow } from '@/app/hooks/useNow';
import {
    askConnexToolCardAffordances,
    askConnexToolCardStatus,
    askConnexToolOutcomeSummary,
    askConnexToolRequestSummary,
    askConnexToolTargetHref,
    type AskConnexToolAction,
    type AskConnexToolCardFailure,
    type AskConnexToolCardState,
    type AskConnexToolSummaryLabels,
} from '@/app/lib/askConnex';
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

/** Localized copy consumed by the presentational assistant tool-call card. */
export type AskConnexToolCardLabels = {
    actionFailed: string;
    approve: string;
    approveAria: (target: string) => string;
    approving: string;
    beforeApproval: string;
    executedDetail: string;
    executedStatus: string;
    expiredDetail: string;
    expiredStatus: string;
    failedDetail: string;
    failedStatus: string;
    ifApproved: string;
    noChangeYet: string;
    outcome: string;
    pendingDetail: string;
    pendingStatus: string;
    proposalChanged: string;
    proposalPermissionLost: string;
    proposalUnavailable: string;
    recordLink: (target: string) => string;
    reject: string;
    rejectAria: (target: string) => string;
    rejectedDetail: string;
    rejectedStatus: string;
    rejecting: string;
    restrictedTarget: string;
    undo: string;
    undoAria: (target: string) => string;
    undoConflict: string;
    undoneDetail: string;
    undoneStatus: string;
    undoing: string;
    summaries: AskConnexToolSummaryLabels;
};

type AskConnexToolCardProps = {
    card: AskConnexToolCardState;
    labels: AskConnexToolCardLabels;
    actionsDisabled: boolean;
    onAction: (toolCallId: number, action: AskConnexToolAction) => void;
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

function failureMessage(
    failure: AskConnexToolCardFailure,
    labels: AskConnexToolCardLabels,
): string {
    if (failure === 'proposalChanged') return labels.proposalChanged;
    if (failure === 'proposalPermissionLost') return labels.proposalPermissionLost;
    if (failure === 'proposalUnavailable') return labels.proposalUnavailable;
    if (failure === 'undoConflict') return labels.undoConflict;
    return labels.actionFailed;
}

/** Accessible semantic card for proposed, executed, and terminal assistant write actions. */
export default function AskConnexToolCard({
    card,
    labels,
    actionsDisabled,
    onAction,
}: AskConnexToolCardProps) {
    const now = useLiveNow();
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
    const busy = card.pendingAction !== null;

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
        <article aria-busy={busy} className={cn('rounded-xl border p-4', presentation.className)}>
            <div className="flex items-start gap-3">
                <span className="mt-0.5 grid size-8 shrink-0 place-items-center rounded-full bg-background text-foreground ring-1 ring-border">
                    <StatusIcon aria-hidden className="size-4" />
                </span>
                <div className="min-w-0 flex-1 space-y-3">
                    <div aria-live="polite" className="space-y-1">
                        <div className="flex flex-wrap items-start gap-2">
                            <h3 className="min-w-0 flex-1 break-words text-sm font-semibold text-foreground">
                                {requestSummary}
                            </h3>
                            <Badge variant={presentation.badgeVariant}>{presentation.label}</Badge>
                        </div>
                        <p className="text-xs leading-relaxed text-muted-foreground">
                            {presentation.detail}
                        </p>
                    </div>

                    {targetHref !== null && card.target.label !== null ? (
                        <Link
                            href={targetHref}
                            aria-label={labels.recordLink(card.target.label)}
                            className="inline-flex max-w-full items-center gap-1.5 rounded-md text-sm font-medium text-foreground outline-none hover:text-primary focus-visible:ring-2 focus-visible:ring-ring"
                        >
                            <LinkIcon aria-hidden className="size-4 shrink-0" />
                            <span className="truncate">{card.target.label}</span>
                        </Link>
                    ) : (
                        <span className="inline-flex items-center gap-1.5 text-sm text-muted-foreground">
                            <NoSymbolIcon aria-hidden className="size-4 shrink-0" />
                            {labels.restrictedTarget}
                        </span>
                    )}

                    {status === 'proposed' ? (
                        <dl className="grid grid-cols-[minmax(0,1fr)_auto_minmax(0,1fr)] items-start gap-2 text-sm">
                            <div className="min-w-0">
                                <dt className="text-xs text-muted-foreground">{labels.beforeApproval}</dt>
                                <dd className="break-words text-muted-foreground">{labels.noChangeYet}</dd>
                            </div>
                            <ArrowRightIcon aria-hidden className="mt-4 size-4 text-muted-foreground" />
                            <div className="min-w-0">
                                <dt className="text-xs text-muted-foreground">{labels.ifApproved}</dt>
                                <dd className="break-words font-medium text-foreground">{requestSummary}</dd>
                            </div>
                        </dl>
                    ) : null}

                    {outcomeSummary !== null ? (
                        <div className="space-y-1 text-sm">
                            <p className="text-xs text-muted-foreground">{labels.outcome}</p>
                            <p className="break-words text-foreground">{outcomeSummary}</p>
                        </div>
                    ) : null}

                    {card.failure !== null ? (
                        <div role="alert" className="flex items-start gap-2 text-xs leading-relaxed text-destructive">
                            <ExclamationTriangleIcon aria-hidden className="mt-0.5 size-4 shrink-0" />
                            <span>{failureMessage(card.failure, labels)}</span>
                        </div>
                    ) : null}

                    {affordances.length > 0 ? (
                        <div className="flex flex-wrap justify-end gap-2 pt-1">
                            {affordances.includes('reject') ? (
                                <Button
                                    type="button"
                                    variant="ghost"
                                    size="sm"
                                    aria-label={labels.rejectAria(targetName)}
                                    disabled={busy || actionsDisabled}
                                    onClick={() => onAction(card.id, 'reject')}
                                >
                                    {card.pendingAction === 'reject' ? labels.rejecting : labels.reject}
                                </Button>
                            ) : null}
                            {affordances.includes('approve') ? (
                                <Button
                                    type="button"
                                    size="sm"
                                    aria-label={labels.approveAria(targetName)}
                                    disabled={busy || actionsDisabled}
                                    onClick={() => onAction(card.id, 'approve')}
                                >
                                    {card.pendingAction === 'approve' ? labels.approving : labels.approve}
                                </Button>
                            ) : null}
                            {affordances.includes('undo') ? (
                                <Button
                                    type="button"
                                    variant="outline"
                                    size="sm"
                                    aria-label={labels.undoAria(targetName)}
                                    disabled={busy || actionsDisabled}
                                    onClick={() => onAction(card.id, 'undo')}
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
