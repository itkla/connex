'use client';

import { useMemo } from 'react';
import {
    CheckCircleIcon,
    ChevronRightIcon,
    ClockIcon,
    ExclamationTriangleIcon,
    MinusCircleIcon,
} from '@heroicons/react/24/outline';

import AskConnexAnswerBlock from '@/app/components/ask-connex/AskConnexAnswerBlocks';
import {
    answerInstant,
    evidenceCaveats,
    type AskConnexAnswerDocumentLabels,
} from '@/app/components/ask-connex/answerDocument';
import type {
    AiChatAnswerDocument,
    AiChatCoverage,
    AiChatProgressItem,
} from '@/app/lib/types';
import { Badge } from '@/components/ui/badge';

export type { AskConnexAnswerDocumentLabels };

function ProgressIcon({ status }: { status: AiChatProgressItem['status'] }) {
    if (status === 'complete') return <CheckCircleIcon className="size-4 text-primary" />;
    if (status === 'running') return <ClockIcon className="size-4 animate-pulse text-muted-foreground motion-reduce:animate-none" />;
    if (status === 'skipped' || status === 'cancelled') {
        return <MinusCircleIcon className="size-4 text-muted-foreground" />;
    }
    return <ExclamationTriangleIcon className="size-4 text-destructive" />;
}

/** Displays the trusted durable milestone trail for live or settled turns. */
export function AskConnexCheckedTrail({
    progress,
    labels,
    expanded = false,
}: {
    progress: AiChatProgressItem[];
    labels: AskConnexAnswerDocumentLabels;
    expanded?: boolean;
}) {
    if (progress.length === 0) return null;
    return (
        <details open={expanded || undefined} className="group border-t border-border pt-3 text-xs text-muted-foreground">
            <summary className="flex min-h-8 cursor-pointer list-none items-center gap-1.5 rounded-md font-medium text-foreground outline-none focus-visible:ring-2 focus-visible:ring-ring [&::-webkit-details-marker]:hidden">
                <ChevronRightIcon className="size-3.5 shrink-0 transition-transform duration-(--motion-micro) group-open:rotate-90 motion-reduce:transition-none" />
                {labels.whatChecked}
            </summary>
            <ol className="mt-2 space-y-2 pl-1">
                {progress.map((item) => (
                    <li key={`${item.seq}:${item.source}`} className="flex min-w-0 items-start gap-2">
                        <span className="mt-0.5 shrink-0"><ProgressIcon status={item.status} /></span>
                        <span className="min-w-0 flex-1">
                            <span className="font-medium text-foreground">
                                {labels.progressSource(item.source)}
                            </span>
                            <span className="ml-1.5">{labels.progressStatus(item.status)}</span>
                            {item.count !== null ? (
                                <span className="ml-1.5">{labels.progressCount(item.count)}</span>
                            ) : null}
                            {item.truncated ? <span className="ml-1.5">{labels.truncated}</span> : null}
                        </span>
                    </li>
                ))}
            </ol>
        </details>
    );
}

/**
 * The quiet expandable coverage disclosure. Status, truncation, and freshness stay in the summary
 * because a partial or stale answer must never be readable only after an expand; the source and
 * exclusion detail sits inside.
 */
export function AskConnexCoverageDisclosure({
    coverage,
    labels,
}: {
    coverage: AiChatCoverage;
    labels: AskConnexAnswerDocumentLabels;
}) {
    const sources = Array.isArray(coverage.sources) ? coverage.sources : [];
    const exclusions = Array.isArray(coverage.exclusions) ? coverage.exclusions : [];
    const asOf = answerInstant(coverage.asOf);
    const periodStart = answerInstant(coverage.periodStart);
    const periodEnd = answerInstant(coverage.periodEnd);
    const hasDetail = sources.length > 0
        || exclusions.length > 0
        || asOf !== null
        || periodStart !== null
        || periodEnd !== null;
    const headline = (
        <>
            <span className="font-medium text-foreground">{labels.coverage}</span>
            <Badge variant={coverage.status === 'complete' ? 'secondary' : 'outline'}>
                {labels.coverageStatus(coverage.status)}
            </Badge>
            {coverage.truncated ? <span>{labels.truncated}</span> : null}
            {asOf ? (
                <time dateTime={asOf}>
                    {labels.freshness}
                    {': '}
                    {labels.relativeTime(asOf)}
                </time>
            ) : null}
        </>
    );

    if (!hasDetail) {
        return (
            <div className="flex flex-wrap items-center gap-x-2 gap-y-1 border-t border-border pt-3 text-xs text-muted-foreground">
                {headline}
            </div>
        );
    }

    return (
        <details className="group border-t border-border pt-3 text-xs text-muted-foreground">
            <summary className="flex min-h-8 cursor-pointer list-none flex-wrap items-center gap-x-2 gap-y-1 rounded-md outline-none focus-visible:ring-2 focus-visible:ring-ring [&::-webkit-details-marker]:hidden">
                <ChevronRightIcon className="size-3.5 shrink-0 transition-transform duration-(--motion-micro) group-open:rotate-90 motion-reduce:transition-none" />
                {headline}
            </summary>
            <div className="mt-2 space-y-1.5 pl-5">
                {asOf ? (
                    <p>
                        <span className="font-medium text-foreground">{labels.freshness}:</span>{' '}
                        {labels.absoluteTime(asOf)}
                    </p>
                ) : null}
                {periodStart || periodEnd ? (
                    <p>{labels.period(
                        periodStart ? labels.absoluteTime(periodStart) : '—',
                        periodEnd ? labels.absoluteTime(periodEnd) : '—',
                    )}</p>
                ) : null}
                {sources.length > 0 ? (
                    <p>
                        <span className="font-medium text-foreground">{labels.sources}:</span>{' '}
                        {sources.map(labels.source).join(', ')}
                    </p>
                ) : null}
                {exclusions.length > 0 ? (
                    <p>
                        <span className="font-medium text-foreground">{labels.exclusions}:</span>{' '}
                        {exclusions.map(labels.exclusion).join(', ')}
                    </p>
                ) : null}
            </div>
        </details>
    );
}

/**
 * Renders a flat, evidence-first answer document without exposing provider reasoning.
 *
 * The document owns no surface of its own: an assistant answer uses the panel's full width like a
 * document rather than sitting in a bubble, which is what lets the structured kinds bring their own
 * bordered components without nesting a card inside a card.
 */
export default function AskConnexAnswerDocument({
    document,
    labels,
}: {
    document: AiChatAnswerDocument;
    labels: AskConnexAnswerDocumentLabels;
}) {
    const caveats = useMemo(
        () => evidenceCaveats(document.coverage, labels),
        [document.coverage, labels],
    );
    return (
        <article className="w-full space-y-5 text-sm text-foreground">
            {document.blocks.map((block, index) => (
                <AskConnexAnswerBlock
                    key={`${block.kind}:${index}`}
                    block={block}
                    caveats={caveats}
                    labels={labels}
                />
            ))}
            <AskConnexCoverageDisclosure coverage={document.coverage} labels={labels} />
            <AskConnexCheckedTrail progress={document.progress} labels={labels} />
        </article>
    );
}
