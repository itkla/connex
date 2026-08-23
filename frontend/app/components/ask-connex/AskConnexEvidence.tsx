'use client';

import { useRef, useState } from 'react';
import {
    ArrowTopRightOnSquareIcon,
    ExclamationTriangleIcon,
    LinkIcon,
} from '@heroicons/react/24/outline';
import Link from 'next/link';

import {
    ANSWER_ROW_PLACEHOLDER,
    citationKey,
    type AskConnexAnswerDocumentLabels,
} from '@/app/components/ask-connex/answerDocument';
import { askConnexCitationHref } from '@/app/lib/askConnex';
import type { AiChatCitation } from '@/app/lib/types';
import { Button } from '@/components/ui/button';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import {
    ResponsiveDialog,
    ResponsiveDialogClose,
    ResponsiveDialogContent,
    ResponsiveDialogDescription,
    ResponsiveDialogFooter,
    ResponsiveDialogHeader,
    ResponsiveDialogTitle,
} from '@/components/ui/responsive-dialog';

/** The display name for a citation, falling back to its record kind when none was projected. */
export function citationLabel(
    citation: AiChatCitation,
    labels: AskConnexAnswerDocumentLabels,
): string {
    return citation.label?.trim() || labels.citationKind(citation.kind);
}

/**
 * The label a citation's timestamp is filed under.
 *
 * A snapshot recorded by the answering turn is the answer's own freshness. A message stored before
 * snapshots existed carries none, so its timestamp is a live read of the record as it stands now
 * and is filed under the record instead — the two must never read alike.
 */
function freshnessLabel(
    citation: AiChatCitation,
    labels: AskConnexAnswerDocumentLabels,
): string {
    return citation.observed ? labels.freshness : labels.freshnessCurrent;
}

function Freshness({
    instant,
    labels,
    className,
}: {
    instant: string;
    labels: AskConnexAnswerDocumentLabels;
    className?: string;
}) {
    return (
        <time dateTime={instant} className={className}>
            {labels.relativeTime(instant)}
            {' · '}
            {labels.absoluteTime(instant)}
        </time>
    );
}

/**
 * The detail an evidence inspector shows for one citation. Exported so the same body can be
 * rendered and asserted without driving the surrounding overlay.
 */
export function AskConnexEvidenceDetail({
    citation,
    caveats,
    labels,
}: {
    citation: AiChatCitation;
    caveats: string[];
    labels: AskConnexAnswerDocumentLabels;
}) {
    const detail = citation.detail?.trim();
    return (
        <dl className="space-y-3 text-sm">
            {detail ? (
                <div className="space-y-1">
                    <dt className="text-xs font-medium text-muted-foreground">{labels.evidenceDetail}</dt>
                    <dd className="break-words whitespace-pre-wrap text-foreground">{detail}</dd>
                </div>
            ) : null}
            <div className="space-y-1">
                <dt className="text-xs font-medium text-muted-foreground">
                    {freshnessLabel(citation, labels)}
                </dt>
                <dd className="text-foreground">
                    {citation.asOf
                        ? <Freshness instant={citation.asOf} labels={labels} />
                        : ANSWER_ROW_PLACEHOLDER}
                </dd>
            </div>
            {caveats.length > 0 ? (
                <div className="space-y-1">
                    <dt className="text-xs font-medium text-muted-foreground">{labels.sourceLimits}</dt>
                    <dd>
                        <ul className="space-y-1">
                            {caveats.map((caveat) => (
                                <li key={caveat} className="flex items-start gap-1.5 text-xs text-muted-foreground">
                                    <ExclamationTriangleIcon aria-hidden className="mt-0.5 size-3.5 shrink-0" />
                                    <span className="break-words">{caveat}</span>
                                </li>
                            ))}
                        </ul>
                    </dd>
                </div>
            ) : null}
        </dl>
    );
}

/**
 * The second step of the evidence escalation: what the anchored peek shows. It names the record,
 * quotes what the citation supports, dates it, and offers both the deeper inspector and the record
 * itself, so a reader can stop at whichever depth answers the question.
 *
 * Following the record link dismisses the peek: the assistant surface deliberately survives the
 * route change, so an anchored popup left open would sit on top of the record it just opened.
 */
export function AskConnexEvidencePeekBody({
    citation,
    labels,
    onEscalate,
    onNavigate,
}: {
    citation: AiChatCitation;
    labels: AskConnexAnswerDocumentLabels;
    onEscalate: () => void;
    onNavigate: () => void;
}) {
    const label = citationLabel(citation, labels);
    const detail = citation.detail?.trim();
    return (
        <div className="space-y-3">
            <div className="space-y-1">
                <p className="text-sm font-semibold break-words text-foreground">{label}</p>
                <p className="text-xs text-muted-foreground">{labels.citationKind(citation.kind)}</p>
            </div>
            {detail ? (
                <p className="line-clamp-4 text-xs leading-relaxed break-words text-foreground">
                    {detail}
                </p>
            ) : null}
            {citation.asOf ? (
                <p className="text-xs text-muted-foreground">
                    <span className="font-medium text-foreground">
                        {freshnessLabel(citation, labels)}
                        {': '}
                    </span>
                    <Freshness instant={citation.asOf} labels={labels} />
                </p>
            ) : null}
            <div className="flex flex-wrap items-center gap-2">
                <Button type="button" variant="outline" size="inline" onClick={onEscalate}>
                    {labels.moreDetail}
                </Button>
                <Button asChild variant="ghost" size="inline">
                    <Link href={askConnexCitationHref(citation)} onClick={onNavigate}>
                        {labels.openRecord}
                        <ArrowTopRightOnSquareIcon aria-hidden className="size-3.5" />
                    </Link>
                </Button>
            </div>
        </div>
    );
}

/**
 * The third step of the evidence escalation: a focused surface carrying the excerpt, freshness, and
 * the limits the answer declared, plus the normal authorized link out to the record itself. It uses
 * the shared responsive dialog, so it is a centered dialog on desktop and a bottom sheet on touch
 * rather than an assistant-only overlay grammar.
 *
 * Following the record link closes the inspector for the same reason the peek closes: the session
 * survives the route change, so the overlay would otherwise sit on top of the record it opened.
 */
export function AskConnexEvidenceInspector({
    citation,
    caveats,
    labels,
    open,
    onOpenChange,
    onCloseComplete,
}: {
    citation: AiChatCitation;
    caveats: string[];
    labels: AskConnexAnswerDocumentLabels;
    open: boolean;
    onOpenChange: (open: boolean) => void;
    onCloseComplete?: () => void;
}) {
    const label = citationLabel(citation, labels);
    return (
        <ResponsiveDialog open={open} onOpenChange={onOpenChange} onCloseComplete={onCloseComplete}>
            <ResponsiveDialogContent className="sm:max-w-md">
                <ResponsiveDialogHeader className="px-4 pt-4 sm:px-0 sm:pt-0">
                    <ResponsiveDialogTitle className="break-words">{label}</ResponsiveDialogTitle>
                    <ResponsiveDialogDescription>
                        {labels.citationKind(citation.kind)}
                    </ResponsiveDialogDescription>
                </ResponsiveDialogHeader>
                <div className="px-4 py-4 sm:px-0">
                    <AskConnexEvidenceDetail citation={citation} caveats={caveats} labels={labels} />
                </div>
                <ResponsiveDialogFooter className="border-t border-border px-4 py-4 sm:border-0 sm:px-0 sm:py-0">
                    <ResponsiveDialogClose asChild>
                        <Button type="button" variant="outline" size="dialog">{labels.dismiss}</Button>
                    </ResponsiveDialogClose>
                    <Button asChild size="dialog">
                        <Link
                            href={askConnexCitationHref(citation)}
                            onClick={() => onOpenChange(false)}
                        >
                            {labels.openRecord}
                            <ArrowTopRightOnSquareIcon aria-hidden className="size-3.5" />
                        </Link>
                    </Button>
                </ResponsiveDialogFooter>
            </ResponsiveDialogContent>
        </ResponsiveDialog>
    );
}

/**
 * One source marker. Pressing it opens the anchored peek; the peek escalates to the inspector and
 * both offer the same authorized deep link, so a reader can stop at whichever depth answers the
 * question. The peek is closed before the inspector opens so the two surfaces never animate over
 * each other, and whichever surface opened the record closes itself on the way out so the reader
 * arrives at the record rather than at an overlay covering it.
 */
export function AskConnexEvidenceMarker({
    citation,
    caveats,
    labels,
}: {
    citation: AiChatCitation;
    caveats: string[];
    labels: AskConnexAnswerDocumentLabels;
}) {
    const [peekOpen, setPeekOpen] = useState(false);
    const [inspectorOpen, setInspectorOpen] = useState(false);
    const markerRef = useRef<HTMLButtonElement>(null);
    const label = citationLabel(citation, labels);

    return (
        <>
            <Popover open={peekOpen} onOpenChange={setPeekOpen}>
                <PopoverTrigger
                    ref={markerRef}
                    type="button"
                    className="inline-flex min-h-7 max-w-56 items-center gap-1.5 rounded-full border border-border px-2.5 py-1 text-xs text-muted-foreground outline-none transition-colors duration-(--motion-micro) hover:bg-muted hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring motion-safe:active:translate-y-px motion-reduce:transition-none"
                >
                    <LinkIcon aria-hidden className="size-3 shrink-0" />
                    <span className="truncate">{label}</span>
                </PopoverTrigger>
                <PopoverContent align="start" aria-label={label} className="w-72">
                    <AskConnexEvidencePeekBody
                        citation={citation}
                        labels={labels}
                        onNavigate={() => setPeekOpen(false)}
                        onEscalate={() => {
                            setPeekOpen(false);
                            setInspectorOpen(true);
                        }}
                    />
                </PopoverContent>
            </Popover>
            <AskConnexEvidenceInspector
                citation={citation}
                caveats={caveats}
                labels={labels}
                open={inspectorOpen}
                onOpenChange={setInspectorOpen}
                onCloseComplete={() => markerRef.current?.focus()}
            />
        </>
    );
}

/** The row of source markers under a block or a structured row. */
export function AskConnexEvidenceRow({
    evidence,
    caveats,
    labels,
}: {
    evidence: AiChatCitation[];
    caveats: string[];
    labels: AskConnexAnswerDocumentLabels;
}) {
    if (evidence.length === 0) return null;
    return (
        <div className="flex flex-wrap items-center gap-1.5" aria-label={labels.evidence}>
            {evidence.map((citation) => (
                <AskConnexEvidenceMarker
                    key={citationKey(citation)}
                    citation={citation}
                    caveats={caveats}
                    labels={labels}
                />
            ))}
        </div>
    );
}

/**
 * The marker a factual block carries when it reached the viewer with no authorized source at all.
 * Rendering nothing would let an unsourced claim read exactly like an evidenced one.
 */
export function AskConnexUnsupportedEvidence({
    labels,
}: {
    labels: AskConnexAnswerDocumentLabels;
}) {
    return (
        <p className="flex items-start gap-1.5 rounded-md border border-dashed border-border px-2.5 py-1.5 text-xs text-muted-foreground">
            <ExclamationTriangleIcon aria-hidden className="mt-px size-3.5 shrink-0" />
            <span className="break-words">{labels.unsupported}</span>
        </p>
    );
}
