'use client';

import {
    ArrowsPointingOutIcon,
    ExclamationTriangleIcon,
    NoSymbolIcon,
} from '@heroicons/react/24/outline';
import Link from 'next/link';

import {
    askConnexFailureMessage,
    AskConnexChangeNotice,
    AskConnexChangeRow,
    type AskConnexToolCardLabels,
} from '@/app/components/ask-connex/AskConnexToolCard';
import {
    askConnexProposalAppliable,
    askConnexToolRequestSummary,
    askConnexToolTargetHref,
    type AskConnexProposalGroup,
    type AskConnexToolAction,
} from '@/app/lib/askConnex';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { cn } from '@/lib/utils';

/** Localized copy for reviewing several proposals from one answer together. */
export type AskConnexProposalReviewLabels = {
    /** How many changes this answer wants to make. */
    heading: (count: number) => string;
    /** How many of them the server established as still applicable. */
    applicable: (applicable: number, count: number) => string;
    /** The batch the member has kept, stated before they commit to it. */
    selected: (count: number) => string;
    applySelected: (count: number) => string;
    applying: string;
    include: (target: string) => string;
    openFullView: string;
    /** Every change in this answer is held up by something, so there is nothing to apply. */
    noneApplicable: string;
    /** How many of this answer's changes went through. */
    applied: (count: number) => string;
    /** How many the member decided against. */
    discarded: (count: number) => string;
    /** How many are still waiting because their last attempt was turned away. */
    failed: (count: number) => string;
};

type AskConnexProposalReviewProps = {
    group: AskConnexProposalGroup;
    labels: AskConnexProposalReviewLabels;
    cardLabels: AskConnexToolCardLabels;
    actionsDisabled: boolean;
    onToggleInclusion: (toolCallId: number) => void;
    onAction: (toolCallId: number, action: AskConnexToolAction) => void;
    onApplySelected: (toolCallIds: number[]) => void | Promise<void>;
};

/**
 * What has become of this answer's changes so far, stated only where something has become of them.
 *
 * A batch that half succeeded is the case this exists for: three refusals and two writes is one
 * outcome, not five silent rows and a pair of toasts that have already gone. Counts that are zero
 * are left out rather than written as zero, because "0 discarded" is noise on a review nobody has
 * touched yet.
 */
function outcomeCounts(
    group: AskConnexProposalGroup,
    labels: AskConnexProposalReviewLabels,
): string | null {
    const parts: string[] = [];
    if (group.applied > 0) parts.push(labels.applied(group.applied));
    if (group.discarded > 0) parts.push(labels.discarded(group.discarded));
    if (group.failed > 0) parts.push(labels.failed(group.failed));
    return parts.length === 0 ? null : parts.join(' · ');
}

/**
 * The compact form: how many changes wait, and the way through to reviewing them properly.
 *
 * The drawer states the decision and hands it off rather than trying to hold it. Several exact
 * record changes, each with its own before-and-after values and its own reason to hesitate, is not
 * a thing to squeeze into a panel beside a conversation — and a member deciding three changes at
 * once needs them all visible at the same time, which the panel cannot give them.
 */
export function AskConnexProposalReviewSummary({
    group,
    labels,
    onOpenFullView,
}: {
    group: AskConnexProposalGroup;
    labels: AskConnexProposalReviewLabels;
    onOpenFullView: () => void;
}) {
    const summaryOutcome = outcomeCounts(group, labels);
    return (
        <section
            aria-label={labels.heading(group.cards.length)}
            className="space-y-2 rounded-xl border border-primary/25 bg-background p-4"
        >
            <div className="space-y-1">
                <h3 className="text-sm font-semibold text-foreground">
                    {labels.heading(group.cards.length)}
                </h3>
                <p className="text-xs leading-relaxed text-muted-foreground">
                    {group.applicable === 0
                        ? labels.noneApplicable
                        : labels.applicable(group.applicable, group.cards.length)}
                </p>
                {summaryOutcome !== null ? (
                    <p className="text-xs text-muted-foreground">{summaryOutcome}</p>
                ) : null}
            </div>
            <div className="flex justify-end">
                <Button type="button" variant="outline" size="dialog" onClick={onOpenFullView}>
                    <ArrowsPointingOutIcon aria-hidden className="size-4" />
                    {labels.openFullView}
                </Button>
            </div>
        </section>
    );
}

/**
 * Several proposals from one answer, reviewed as the one decision they are.
 *
 * Each row carries its own record, its own exact before-and-after values, and its own reason to
 * hesitate, because approving three changes at once is only safe if each of the three was actually
 * read. Taking a row out of the batch leaves its proposal untouched and still decidable on its own;
 * only discarding rejects it. The footer states the batch that pressing apply would commit to,
 * counted from the rows that are both kept and still applicable, so the number on the button is
 * the number of records that will change.
 *
 * Every change is applied through the same single-proposal path the individual cards use, one at a
 * time, so each row reports its own outcome and a batch that half succeeds says exactly which half.
 */
export default function AskConnexProposalReview({
    group,
    labels,
    cardLabels,
    actionsDisabled,
    onToggleInclusion,
    onAction,
    onApplySelected,
}: AskConnexProposalReviewProps) {
    const applying = group.cards.some((card) => card.pendingAction === 'approve');
    const selectedIds = group.cards.reduce<number[]>((ids, card) => {
        if (group.included.has(card.id) && askConnexProposalAppliable(card)) ids.push(card.id);
        return ids;
    }, []);
    const outcome = outcomeCounts(group, labels);

    return (
        <section
            aria-label={labels.heading(group.cards.length)}
            className="overflow-hidden rounded-xl border border-primary/25 bg-background"
        >
            <header className="space-y-1 px-4 py-3">
                <h3 className="text-sm font-semibold text-foreground">
                    {labels.heading(group.cards.length)}
                </h3>
                <p className="text-xs leading-relaxed text-muted-foreground">
                    {group.applicable === 0
                        ? labels.noneApplicable
                        : labels.applicable(group.applicable, group.cards.length)}
                </p>
            </header>

            <ul className="divide-y divide-border border-y border-border">
                {group.cards.map((card) => {
                    const targetHref = askConnexToolTargetHref(card.target);
                    const targetName = card.target.label ?? cardLabels.restrictedTarget;
                    const appliable = askConnexProposalAppliable(card);
                    const included = group.included.has(card.id) && appliable;
                    const busy = card.pendingAction !== null;
                    return (
                        <li
                            key={card.id}
                            className={cn(
                                'flex items-start gap-3 px-4 py-3',
                                card.failure !== null ? 'bg-destructive/5' : null,
                            )}
                        >
                            <Checkbox
                                checked={included}
                                disabled={busy
                                    || actionsDisabled
                                    || !appliable
                                    || card.status !== 'proposed'}
                                aria-label={labels.include(targetName)}
                                onCheckedChange={() => onToggleInclusion(card.id)}
                                className="relative mt-1 shrink-0 before:absolute before:-inset-3.5 before:content-['']"
                            />
                            <div className="min-w-0 flex-1 space-y-2">
                                <div className="space-y-1">
                                    <p className="break-words text-sm font-medium text-foreground">
                                        {askConnexToolRequestSummary(card, cardLabels.summaries)}
                                    </p>
                                    {targetHref !== null && card.target.label !== null ? (
                                        <Link
                                            href={targetHref}
                                            aria-label={cardLabels.recordLink(card.target.label)}
                                            className="inline-flex max-w-full items-center rounded-md text-xs text-muted-foreground outline-none hover:text-primary focus-visible:ring-2 focus-visible:ring-ring"
                                        >
                                            <span className="truncate">{card.target.label}</span>
                                        </Link>
                                    ) : (
                                        <span className="inline-flex items-center gap-1.5 text-xs text-muted-foreground">
                                            <NoSymbolIcon aria-hidden className="size-3.5 shrink-0" />
                                            {cardLabels.restrictedTarget}
                                        </span>
                                    )}
                                </div>
                                {card.change !== null ? (
                                    <>
                                        <AskConnexChangeRow change={card.change} labels={cardLabels} />
                                        <AskConnexChangeNotice
                                            state={card.change.state}
                                            labels={cardLabels}
                                        />
                                    </>
                                ) : null}
                                {card.failure !== null ? (
                                    <div
                                        role="alert"
                                        className="flex items-start gap-2 text-xs leading-relaxed text-destructive"
                                    >
                                        <ExclamationTriangleIcon
                                            aria-hidden
                                            className="mt-0.5 size-4 shrink-0"
                                        />
                                        <span>
                                            {askConnexFailureMessage(card.failure, cardLabels)}
                                        </span>
                                    </div>
                                ) : null}
                                <div className="flex justify-end">
                                    <Button
                                        type="button"
                                        variant="ghost"
                                        size="dialog"
                                        aria-label={cardLabels.discardAria(targetName)}
                                        disabled={busy || actionsDisabled}
                                        onClick={() => onAction(card.id, 'reject')}
                                    >
                                        {card.pendingAction === 'reject'
                                            ? cardLabels.discarding
                                            : cardLabels.discard}
                                    </Button>
                                </div>
                            </div>
                        </li>
                    );
                })}
            </ul>

            <footer className="flex flex-wrap items-center justify-between gap-3 px-4 py-3">
                <div aria-live="polite" className="space-y-0.5 text-xs text-muted-foreground">
                    <p>{labels.selected(group.selected)}</p>
                    {outcome !== null ? <p>{outcome}</p> : null}
                </div>
                <Button
                    type="button"
                    size="dialog"
                    disabled={applying || actionsDisabled || selectedIds.length === 0}
                    onClick={() => void onApplySelected(selectedIds)}
                >
                    {applying ? labels.applying : labels.applySelected(group.selected)}
                </Button>
            </footer>
        </section>
    );
}
