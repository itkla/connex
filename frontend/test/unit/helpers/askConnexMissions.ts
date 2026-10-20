import type { AiAgentOutput, AiAgentProposalDecisionResult } from '@/app/lib/types';
import { askConnexCard, askConnexChange } from '@/test/unit/helpers/askConnexToolCards';

/** Exact frozen membership for mission review boundary tests. */
export function missionProposalOutput(): AiAgentOutput {
    return {
        schemaVersion: 1, runId: 12, outputRevision: '3', report: {
            messageId: 20, markdown: 'Review ready', citations: [],
            coverage: { processed: 1, skipped: 0, failed: 0, unprocessed: 0 }, generatedAt: '2026-10-19T10:00:00Z',
        }, proposalSet: {
            id: 12, revision: '2', frozen: true, frozenAt: '2026-10-19T10:00:00Z',
            firstPreparedAt: '2026-10-19T09:00:00Z', reviewExpiresAt: '2026-10-22T09:00:00Z',
            items: [1, 2].map((ordinal) => ({
                actionSlot: `6e922baf-d2bc-49cf-8bf0-b2d086dd0a3${ordinal}`,
                preparationId: `a4d9f701-b418-4208-8a0a-e727fd4dc94${ordinal}`,
                ordinal, preparedAt: '2026-10-19T09:00:00Z',
                card: askConnexCard({ id: ordinal + 30, change: askConnexChange(), reviewExpiresAt: '2026-10-22T09:00:00Z' }),
            })),
            derivedCounts: { pending: 2, executed: 0, rejected: 0, withdrawn: 0, undone: 0 },
        }, autoWriteReceipts: [],
    };
}

/** A committed first item and a truthful disarmed second item. */
export function missionPartialDecision(outcome: 'refused' | 'indeterminate' = 'refused'): AiAgentProposalDecisionResult {
    const output = missionProposalOutput();
    return {
        complete: false, stopReason: 'batch_stopped', stateVersion: '4', outputRevision: '4', contentWithheld: false,
        items: output.proposalSet.items.map((item, index) => ({ actionSlot: item.actionSlot,
            outcome: index === 0 ? 'executed' : outcome, code: index === 0 ? null : 'record_changed' })),
    };
}
