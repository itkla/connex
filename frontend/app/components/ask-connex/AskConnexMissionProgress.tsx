'use client';

import { useFormatter, useTranslations } from 'next-intl';

import AskConnexProposalReview, { type AskConnexProposalReviewLabels } from '@/app/components/ask-connex/AskConnexProposalReview';
import type { AskConnexToolCardLabels } from '@/app/components/ask-connex/AskConnexToolCard';
import type { AskConnexProposalGroup, AskConnexToolAction } from '@/app/lib/askConnex';
import { useProposalReviewNow } from '@/app/hooks/useProposalReviewNow';
import AskConnexMarkdown from '@/app/components/ask-connex/AskConnexMarkdown';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Skeleton } from '@/components/ui/skeleton';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import type { AskConnexMissionProblem } from '@/app/lib/askConnexMissions';
import type { AiAgentOutput, AiAgentProposalDecisionResult, AiAgentRun, AiAgentRunControl } from '@/app/lib/types';

const CONTROLS: readonly AiAgentRunControl[] = ['pause', 'resume', 'cancel'];

/** Minimal owner-visible progress within the existing session, backed by durable mission state. */
export default function AskConnexMissionProgress({
    runs, loading, problem, pendingId, output, hasMore, onControl, onOutput, onCloseOutput, onRetry, onMore, proposalGroup, decisionResult, reviewLabels, cardLabels, onToggleInclusion, onProposalAction, onApplySelected,
}: {
    runs: AiAgentRun[];
    loading: boolean;
    problem: AskConnexMissionProblem | null;
    pendingId: number | null;
    output: AiAgentOutput | null;
    hasMore: boolean;
    proposalGroup?: AskConnexProposalGroup | null;
    decisionResult?: AiAgentProposalDecisionResult | null;
    reviewLabels?: AskConnexProposalReviewLabels;
    cardLabels?: AskConnexToolCardLabels;
    onToggleInclusion?: (id: number) => void;
    onProposalAction?: (id: number, action: AskConnexToolAction) => void;
    onApplySelected?: (ids: number[]) => void | Promise<void>;
    onControl: (run: AiAgentRun, action: AiAgentRunControl) => void;
    onOutput: (run: AiAgentRun) => void;
    onCloseOutput: () => void;
    onRetry: () => void;
    onMore: () => void;
}) {
    const t = useTranslations('AskConnex.missions');
    const format = useFormatter();
    const now = useProposalReviewNow(runs.map((run) => run.reviewExpiresAt));
    const outputRun = runs.find((run) => run.id === output?.runId);
    const preparationOutput = outputRun?.missionType === 'record_preparation_v1';
    if (!loading && runs.length === 0 && problem === null) return null;
    return (
        <section aria-label={t('title')} className="space-y-3 border-b border-border pb-4">
            {loading ? <Skeleton className="h-12 w-full rounded-lg" /> : null}
            {problem ? (
                <div role="status" className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
                    <p>{t(`problems.${problem}`)}</p>
                    <Button type="button" variant="ghost" size="inline" onClick={onRetry}>{t('retry')}</Button>
                </div>
            ) : null}
            {runs.map((run) => (
                <div key={run.id} className="space-y-2" aria-busy={pendingId === run.id}>
                    <div className="flex flex-wrap items-center gap-2">
                        <span className="text-sm font-medium">{t(run.missionType === 'record_preparation_v1' ? 'preparation.title' : 'title')}</span>
                        <Badge variant="outline">{t(`states.${run.state}`)}</Badge>
                    </div>
                    <p className="text-sm text-muted-foreground">
                        {run.contentWithheld ? t('withheld') : run.goal}
                    </p>
                    {!run.contentWithheld && run.progress ? (
                        <p role="status" className="text-xs text-muted-foreground">{t(`phases.${run.progress.phase}`)}</p>
                    ) : null}
                    {!run.contentWithheld && run.state === 'paused' ? (
                        <p className="text-xs text-muted-foreground">{t('pausedHelp')}</p>
                    ) : null}
                    {!run.contentWithheld && run.proposalCounts ? (
                        <p role="status" className="text-xs text-muted-foreground">{t('review.pending', { count: run.proposalCounts.pending })}</p>
                    ) : null}
                    {!run.contentWithheld && run.reviewExpiresAt ? (
                        <p className="text-xs text-muted-foreground">{t(now >= Date.parse(run.reviewExpiresAt) ? 'review.expired' : 'review.deadline', {
                            date: format.dateTime(new Date(run.reviewExpiresAt), { dateStyle: 'medium', timeStyle: 'short' }),
                        })}</p>
                    ) : null}
                    <div className="flex flex-wrap gap-2">
                        {CONTROLS.map((action) => run.availableActions.includes(action) && (run.state !== 'waiting_approval' || action === 'cancel') ? (
                            <Button key={action} type="button" variant="outline" size="inline"
                                disabled={pendingId !== null} onClick={() => onControl(run, action)}>
                                {t(`controls.${action}`)}
                            </Button>
                        ) : null)}
                        {run.outputAvailable && !run.contentWithheld && run.availableActions.includes('view_output') ? (
                            <Button type="button" variant="secondary" size="inline" disabled={pendingId !== null}
                                onClick={() => onOutput(run)}>{t(run.missionType === 'record_preparation_v1' ? 'preparation.viewOutput' : 'viewOutput')}</Button>
                        ) : null}
                    </div>
                </div>
            ))}
            {hasMore ? <Button type="button" variant="ghost" size="inline" disabled={pendingId !== null} onClick={onMore}>{t('more')}</Button> : null}
            <Dialog open={output !== null} onOpenChange={(open) => { if (!open) onCloseOutput(); }}>
                <DialogContent className="max-h-[85dvh] overflow-y-auto sm:max-w-2xl">
                    <DialogHeader>
                        <DialogTitle>{t(preparationOutput ? 'preparation.outputTitle' : 'reportTitle')}</DialogTitle>
                        <DialogDescription>{t('reportDescription')}</DialogDescription>
                    </DialogHeader>
                    {output ? (
                        <>
                            <AskConnexMarkdown content={output.report.markdown}
                                allowedRecords={new Set(output.report.citations.map((citation) => `${citation.kind}:${citation.id}`))} />
                            {preparationOutput ? (
                                <div className="space-y-3">
                                    <p className="text-xs text-muted-foreground">{t('review.windowHelp')}</p>
                                    <p className="text-xs text-muted-foreground">{t('review.independent')}</p>
                                    {decisionResult?.items.some((item) => item.outcome === 'indeterminate') ? (
                                        <div role="alert" className="space-y-2">
                                            <p className="text-sm text-destructive">{t('review.indeterminate')}</p>
                                            {outputRun ? <Button type="button" variant="outline" size="inline"
                                                disabled={pendingId !== null} onClick={() => onOutput(outputRun)}>{t('review.reload')}</Button> : null}
                                        </div>
                                    ) : null}
                                    {output.proposalSet.items.length === 0 ? <p>{t('review.empty')}</p>
                                        : proposalGroup && reviewLabels && cardLabels && onToggleInclusion && onProposalAction && onApplySelected ? (
                                            <AskConnexProposalReview group={proposalGroup} labels={reviewLabels} cardLabels={cardLabels}
                                                actionsDisabled={pendingId !== null} onToggleInclusion={onToggleInclusion}
                                                onAction={onProposalAction} onApplySelected={onApplySelected} />
                                        ) : null}
                                    {output.proposalSet.items.length > 0 && output.proposalSet.derivedCounts.pending === 0 ? (
                                        <p role="status" className="text-sm text-muted-foreground">{t('review.complete')}</p>
                                    ) : null}
                                </div>
                            ) : null}
                            <p className="text-xs text-muted-foreground">
                                {t('coverage', output.report.coverage)}
                            </p>
                        </>
                    ) : null}
                </DialogContent>
            </Dialog>
        </section>
    );
}
