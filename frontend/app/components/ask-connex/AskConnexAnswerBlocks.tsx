'use client';

import { useEffect, useState } from 'react';
import {
    ArrowRightCircleIcon,
    ArrowsPointingOutIcon,
    CheckBadgeIcon,
    CheckIcon,
    ExclamationTriangleIcon,
    LightBulbIcon,
    MinusCircleIcon,
    PlusCircleIcon,
    Square2StackIcon,
} from '@heroicons/react/24/outline';

import {
    ANSWER_ROW_PLACEHOLDER,
    UNBOUNDED_ANSWER,
    answerInstant,
    answerListKeys,
    answerRowSignature,
    answerRows,
    blockEvidence,
    isUnsupportedBlock,
    rowCitations,
    withheldRowEvidence,
    type AskConnexAnswerBounds,
    type AskConnexAnswerDocumentLabels,
} from '@/app/components/ask-connex/answerDocument';
import {
    AskConnexEvidenceRow,
    AskConnexUnsupportedEvidence,
} from '@/app/components/ask-connex/AskConnexEvidence';
import { boundedAnswerEntries } from '@/app/lib/askConnexSurface';
import { copyToClipboard } from '@/app/lib/utils';
import type {
    AiChatAnswerBlock,
    AiChatAnswerBlockKind,
    AiChatAnswerRow,
    AiChatCitation,
} from '@/app/lib/types';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';

type BlockProps = {
    block: AiChatAnswerBlock;
    caveats: string[];
    bounds: AskConnexAnswerBounds;
    labels: AskConnexAnswerDocumentLabels;
};

type RowProps = {
    rows: AiChatAnswerRow[];
    caveats: string[];
    labels: AskConnexAnswerDocumentLabels;
};

const EPISTEMIC_ICONS = {
    fact: CheckBadgeIcon,
    inference: LightBulbIcon,
    recommendation: ArrowRightCircleIcon,
    limitation: ExclamationTriangleIcon,
} as const;

type EpistemicKind = keyof typeof EPISTEMIC_ICONS;

/**
 * How long the draft's copy control stays in its confirmed state. This is a settled acknowledgement
 * rather than a transition, so it is deliberately longer than the shared motion tokens.
 */
const COPY_CONFIRMATION_MS = 2000;

function isEpistemicKind(kind: AiChatAnswerBlockKind): kind is EpistemicKind {
    return kind === 'fact' || kind === 'inference' || kind === 'recommendation' || kind === 'limitation';
}

/**
 * The kind marker on an epistemic block. Three independent channels carry the distinction — the
 * word, the icon, and the block's own structure — so nothing here depends on hue.
 */
function KindMarker({
    kind,
    labels,
}: {
    kind: EpistemicKind;
    labels: AskConnexAnswerDocumentLabels;
}) {
    const Icon = EPISTEMIC_ICONS[kind];
    return (
        <span className="inline-flex items-center gap-1.5 text-xs font-semibold text-foreground">
            <Icon aria-hidden className="size-4 shrink-0 text-muted-foreground" />
            {labels.blockKind(kind)}
        </span>
    );
}

function BlockTitle({ title }: { title: string | null }) {
    if (!title) return null;
    return <h3 className="text-sm font-semibold break-words text-foreground">{title}</h3>;
}

function BlockBody({ body, className }: { body: string | null; className?: string }) {
    if (!body) return null;
    return (
        <p className={cn('leading-relaxed break-words whitespace-pre-wrap', className)}>{body}</p>
    );
}

/**
 * What a bounded list withheld, the sources backing what it withheld, and the one place the rest of
 * it can be read.
 *
 * The count is stated rather than implied: a list that simply stops looks like a short list, and a
 * reader deciding whether an answer covered their question has to know the difference. Evidence that
 * belonged to the withheld rows is named here too, under its own label, so bounding never removes
 * the last visible source from a block and never lets uncited rows read as established.
 */
function BoundedFooter({
    hidden,
    total,
    withheld,
    bounds,
    labels,
}: {
    hidden: number;
    total: number;
    withheld?: { evidence: AiChatCitation[]; caveats: string[] };
    bounds: AskConnexAnswerBounds;
    labels: AskConnexAnswerDocumentLabels;
}) {
    if (hidden === 0) return null;
    return (
        <div className="space-y-1.5 pt-0.5">
            <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-muted-foreground">
                <span>{labels.boundedRows(total - hidden, total)}</span>
                {bounds.onOpenFullView ? (
                    <Button type="button" variant="ghost" size="inline" onClick={bounds.onOpenFullView}>
                        <ArrowsPointingOutIcon aria-hidden className="size-3.5" />
                        {labels.viewAll}
                    </Button>
                ) : null}
            </div>
            {withheld && withheld.evidence.length > 0 ? (
                <div className="space-y-1">
                    <p className="text-xs text-muted-foreground">{labels.withheldEvidence}</p>
                    <AskConnexEvidenceRow
                        evidence={withheld.evidence}
                        caveats={withheld.caveats}
                        labels={labels}
                    />
                </div>
            ) : null}
        </div>
    );
}

function BlockItems({
    items,
    ordered,
    bounds,
    labels,
}: {
    items: string[];
    ordered?: boolean;
    bounds: AskConnexAnswerBounds;
    labels: AskConnexAnswerDocumentLabels;
}) {
    if (items.length === 0) return null;
    const { entries, hidden } = boundedAnswerEntries(items, bounds.cap);
    const className = 'space-y-1.5 pl-5 leading-relaxed marker:text-muted-foreground';
    const keys = answerListKeys(entries);
    const children = entries.map((item, index) => (
        <li key={keys[index]} className={cn('break-words', ordered ? 'list-decimal' : 'list-disc')}>
            {item}
        </li>
    ));
    return (
        <div className="space-y-1.5">
            {ordered
                ? <ol className={className}>{children}</ol>
                : <ul className={className}>{children}</ul>}
            <BoundedFooter
                hidden={hidden}
                total={items.length}
                bounds={bounds}
                labels={labels}
            />
        </div>
    );
}

function MetricRows({ rows, caveats, labels }: RowProps) {
    const keys = answerListKeys(rows.map(answerRowSignature));
    return (
        <div className="grid grid-cols-1 gap-px overflow-hidden rounded-xl bg-border ring-1 ring-border sm:grid-cols-2">
            {rows.map((row, index) => (
                <div key={keys[index]} className="flex flex-col gap-1.5 bg-card p-3.5">
                    <span className="text-[0.6875rem] font-medium tracking-[0.12em] break-words text-muted-foreground uppercase">
                        {row.label}
                    </span>
                    <span className="text-2xl leading-none break-words text-foreground tabular-nums">
                        {row.value ?? ANSWER_ROW_PLACEHOLDER}
                    </span>
                    {row.detail ? (
                        <span className="text-xs break-words text-muted-foreground">{row.detail}</span>
                    ) : null}
                    <AskConnexEvidenceRow
                        evidence={rowCitations(row)}
                        caveats={caveats}
                        labels={labels}
                    />
                </div>
            ))}
        </div>
    );
}

function ComparisonRows({ rows, caveats, labels }: RowProps) {
    const keys = answerListKeys(rows.map(answerRowSignature));
    return (
        <dl className="divide-y divide-border overflow-hidden rounded-xl ring-1 ring-border">
            {rows.map((row, index) => (
                <div key={keys[index]} className="space-y-2 px-3 py-2.5">
                    <dt className="text-xs font-medium break-words text-muted-foreground">{row.label}</dt>
                    <div className="grid grid-cols-2 gap-3">
                        <dd className="min-w-0">
                            <span className="sr-only">{labels.comparisonValue}: </span>
                            <span className="block text-sm break-words text-foreground tabular-nums">
                                {row.value ?? ANSWER_ROW_PLACEHOLDER}
                            </span>
                        </dd>
                        <dd className="min-w-0 border-l border-border pl-3">
                            <span className="sr-only">{labels.comparisonAgainst}: </span>
                            <span className="block text-sm break-words text-muted-foreground tabular-nums">
                                {row.detail ?? ANSWER_ROW_PLACEHOLDER}
                            </span>
                        </dd>
                    </div>
                    <AskConnexEvidenceRow
                        evidence={rowCitations(row)}
                        caveats={caveats}
                        labels={labels}
                    />
                </div>
            ))}
        </dl>
    );
}

function TimelineRows({ rows, caveats, labels }: RowProps) {
    const keys = answerListKeys(rows.map(answerRowSignature));
    return (
        <ol className="space-y-0">
            {rows.map((row, index) => {
                const at = answerInstant(row.at);
                return (
                    <li
                        key={keys[index]}
                        className="grid grid-cols-[0.75rem_minmax(0,1fr)] gap-x-3 pb-4 last:pb-0"
                    >
                        <span aria-hidden className="relative flex justify-center">
                            <span className="mt-1.5 size-2 shrink-0 rounded-full bg-muted-foreground/70" />
                            {index < rows.length - 1 ? (
                                <span className="absolute top-4 -bottom-4 w-px bg-border" />
                            ) : null}
                        </span>
                        <div className="min-w-0 space-y-1">
                            <div className="flex flex-wrap items-baseline gap-x-2 gap-y-0.5">
                                <span className="text-sm font-medium break-words text-foreground">{row.label}</span>
                                {at ? (
                                    <time dateTime={at} className="text-xs text-muted-foreground">
                                        {labels.relativeTime(at)}
                                        {' · '}
                                        {labels.absoluteTime(at)}
                                    </time>
                                ) : null}
                            </div>
                            {row.value ? (
                                <p className="text-sm leading-relaxed break-words text-foreground">{row.value}</p>
                            ) : null}
                            {row.detail ? (
                                <p className="text-xs break-words text-muted-foreground">{row.detail}</p>
                            ) : null}
                            <AskConnexEvidenceRow
                                evidence={rowCitations(row)}
                                caveats={caveats}
                                labels={labels}
                            />
                        </div>
                    </li>
                );
            })}
        </ol>
    );
}

function DiffRows({ rows, caveats, labels }: RowProps) {
    const keys = answerListKeys(rows.map(answerRowSignature));
    return (
        <ul className="divide-y divide-border overflow-hidden rounded-xl ring-1 ring-border">
            {rows.map((row, index) => (
                <li key={keys[index]} className="space-y-1.5 px-3 py-2.5">
                    <span className="block text-xs font-medium break-words text-muted-foreground">
                        {row.label}
                    </span>
                    <div className="grid grid-cols-[auto_auto_minmax(0,1fr)] items-start gap-x-2 gap-y-1">
                        <MinusCircleIcon aria-hidden className="mt-0.5 size-3.5 shrink-0 text-muted-foreground" />
                        <span className="text-xs text-muted-foreground">{labels.diffBefore}</span>
                        <span className="text-sm break-words text-muted-foreground line-through decoration-muted-foreground/70">
                            {row.value ?? ANSWER_ROW_PLACEHOLDER}
                        </span>
                        <PlusCircleIcon aria-hidden className="mt-0.5 size-3.5 shrink-0 text-foreground" />
                        <span className="text-xs text-muted-foreground">{labels.diffAfter}</span>
                        <span className="text-sm font-medium break-words text-foreground">
                            {row.detail ?? ANSWER_ROW_PLACEHOLDER}
                        </span>
                    </div>
                    <AskConnexEvidenceRow
                        evidence={rowCitations(row)}
                        caveats={caveats}
                        labels={labels}
                    />
                </li>
            ))}
        </ul>
    );
}

function ExtractionRows({ rows, caveats, labels }: RowProps) {
    const keys = answerListKeys(rows.map(answerRowSignature));
    return (
        <dl className="divide-y divide-border overflow-hidden rounded-xl ring-1 ring-border">
            {rows.map((row, index) => (
                <div
                    key={keys[index]}
                    className="grid grid-cols-1 gap-x-3 gap-y-0.5 px-3 py-2 sm:grid-cols-[minmax(0,8rem)_minmax(0,1fr)]"
                >
                    <dt className="text-xs font-medium break-words text-muted-foreground">{row.label}</dt>
                    <dd className="min-w-0 space-y-1">
                        <span className="block text-sm break-words text-foreground">
                            {row.value ?? ANSWER_ROW_PLACEHOLDER}
                        </span>
                        {row.detail ? (
                            <span className="block text-xs break-words text-muted-foreground">{row.detail}</span>
                        ) : null}
                        <AskConnexEvidenceRow
                            evidence={rowCitations(row)}
                            caveats={caveats}
                            labels={labels}
                        />
                    </dd>
                </div>
            ))}
        </dl>
    );
}

/**
 * Everything the draft actually puts on screen, in the order it is read.
 *
 * A draft is valid with a body, with items, or with both, and the copy control has to hand over the
 * whole thing — copying only the body silently dropped an item list the reader could see. The
 * caption is deliberately excluded: it labels the draft rather than being part of it.
 */
function draftText(block: AiChatAnswerBlock): string {
    return [block.body ?? '', ...block.items]
        .filter((part) => part.trim().length > 0)
        .join('\n');
}

function DraftBlock({ block, labels }: { block: AiChatAnswerBlock; labels: AskConnexAnswerDocumentLabels }) {
    const [copied, setCopied] = useState(false);
    const draft = draftText(block);

    useEffect(() => {
        if (!copied) return;
        const timer = window.setTimeout(() => setCopied(false), COPY_CONFIRMATION_MS);
        return () => window.clearTimeout(timer);
    }, [copied]);
    return (
        <figure className="overflow-hidden rounded-xl ring-1 ring-border">
            <figcaption className="flex items-center justify-between gap-2 border-b border-border bg-muted/50 px-3 py-1.5">
                <span className="min-w-0 truncate text-xs font-medium text-muted-foreground">
                    {block.title ?? labels.blockKind('draft')}
                </span>
                <Button
                    type="button"
                    variant="ghost"
                    size="inline"
                    disabled={draft.length === 0}
                    onClick={() => setCopied(copyToClipboard(draft, labels.copyDraft))}
                >
                    {copied
                        ? <CheckIcon aria-hidden className="size-3.5" />
                        : <Square2StackIcon aria-hidden className="size-3.5" />}
                    {copied ? labels.copyDraftDone : labels.copyDraft}
                </Button>
            </figcaption>
            <div className="px-3 py-2.5">
                <BlockBody body={block.body} className="text-sm text-foreground" />
                <BlockItems items={block.items} bounds={UNBOUNDED_ANSWER} labels={labels} />
            </div>
        </figure>
    );
}

/**
 * The structured presentation for the row-bearing kinds, or null when the block carries no rows.
 *
 * Bounding happens here rather than inside each kind, so every structured kind stops at the same
 * place and says so the same way, and the kinds themselves stay presentational. What bounding must
 * never hide is evidence: the withheld rows' sources travel to the truncation line, which keeps the
 * block's visible sourcing equal to its whole sourcing and so keeps the unsupported marker — decided
 * from the block — honest about what is on screen.
 */
function StructuredRows({ block, caveats, bounds, labels }: BlockProps) {
    const all = answerRows(block);
    if (all.length === 0) return null;
    const { entries: rows, hidden } = boundedAnswerEntries(all, bounds.cap);
    const rowProps: RowProps = { rows, caveats, labels };
    const presentation = block.kind === 'metric'
        ? <MetricRows {...rowProps} />
        : block.kind === 'comparison'
            ? <ComparisonRows {...rowProps} />
            : block.kind === 'timeline'
                ? <TimelineRows {...rowProps} />
                : block.kind === 'diff'
                    ? <DiffRows {...rowProps} />
                    : block.kind === 'extraction'
                        ? <ExtractionRows {...rowProps} />
                        : null;
    if (presentation === null) return null;
    return (
        <div className="space-y-1.5">
            {presentation}
            <BoundedFooter
                hidden={hidden}
                total={all.length}
                withheld={{ evidence: withheldRowEvidence(all.slice(rows.length)), caveats }}
                bounds={bounds}
                labels={labels}
            />
        </div>
    );
}

/**
 * One answer-document block, rendered through the Connex component that matches its kind rather
 * than through a shared prose fallback.
 *
 * `answer` is the lede and carries no kind marker: it is the reply itself, and labelling it would
 * add a heading to every answer for no gain. The epistemic kinds carry a marker plus their own
 * structure. The structured kinds are identified by the component they render as, and fall back to
 * `body`/`items` when a payload arrives without rows.
 */
export default function AskConnexAnswerBlock({ block, caveats, bounds, labels }: BlockProps) {
    const rows = answerRows(block);
    const structured = (
        <StructuredRows block={block} caveats={caveats} bounds={bounds} labels={labels} />
    );
    const evidence = blockEvidence(block);

    if (block.kind === 'draft') {
        return (
            <section className="space-y-2">
                <DraftBlock block={block} labels={labels} />
                <AskConnexEvidenceRow evidence={evidence} caveats={caveats} labels={labels} />
            </section>
        );
    }

    if (block.kind === 'answer') {
        return (
            <section className="space-y-2">
                <BlockTitle title={block.title} />
                <BlockBody body={block.body} className="text-[0.9375rem] text-foreground" />
                <BlockItems items={block.items} bounds={bounds} labels={labels} />
                <AskConnexEvidenceRow evidence={evidence} caveats={caveats} labels={labels} />
                {isUnsupportedBlock(block) ? <AskConnexUnsupportedEvidence labels={labels} /> : null}
            </section>
        );
    }

    if (isEpistemicKind(block.kind)) {
        const marker = <KindMarker kind={block.kind} labels={labels} />;
        if (block.kind === 'inference') {
            return (
                <section className="space-y-2">
                    {marker}
                    <div className="space-y-2 rounded-lg bg-muted/70 px-3 py-2.5">
                        <BlockTitle title={block.title} />
                        <BlockBody body={block.body} className="text-sm text-foreground" />
                        <BlockItems items={block.items} bounds={bounds} labels={labels} />
                    </div>
                    <AskConnexEvidenceRow evidence={evidence} caveats={caveats} labels={labels} />
                </section>
            );
        }
        if (block.kind === 'limitation') {
            return (
                <section className="space-y-2">
                    {marker}
                    <div className="space-y-1.5 rounded-lg border border-dashed border-border px-3 py-2.5 text-xs text-muted-foreground">
                        <BlockTitle title={block.title} />
                        <BlockBody body={block.body} />
                        <BlockItems items={block.items} bounds={bounds} labels={labels} />
                    </div>
                </section>
            );
        }
        return (
            <section className="space-y-2">
                {marker}
                <BlockTitle title={block.title} />
                <BlockBody body={block.body} className="text-sm text-foreground" />
                <BlockItems
                    items={block.items}
                    ordered={block.kind === 'recommendation'}
                    bounds={bounds}
                    labels={labels}
                />
                <AskConnexEvidenceRow evidence={evidence} caveats={caveats} labels={labels} />
                {isUnsupportedBlock(block) ? <AskConnexUnsupportedEvidence labels={labels} /> : null}
            </section>
        );
    }

    return (
        <section className="space-y-2">
            <BlockTitle title={block.title} />
            <BlockBody
                body={block.body}
                className={cn(
                    'text-sm text-foreground',
                    rows.length === 0
                        && block.kind === 'metric'
                        && 'text-2xl leading-snug font-semibold tabular-nums',
                )}
            />
            {structured}
            <BlockItems items={block.items} bounds={bounds} labels={labels} />
            <AskConnexEvidenceRow evidence={evidence} caveats={caveats} labels={labels} />
            {isUnsupportedBlock(block) ? <AskConnexUnsupportedEvidence labels={labels} /> : null}
        </section>
    );
}
