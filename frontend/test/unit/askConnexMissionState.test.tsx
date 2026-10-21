import { act } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { NextIntlClientProvider } from 'next-intl';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import AskConnexMissionProgress from '@/app/components/ask-connex/AskConnexMissionProgress';
import enCommon from '@/messages/en/common.json';
import jaCommon from '@/messages/ja/common.json';
import { installInteractiveDocument } from '@/test/unit/helpers/interactiveDocument';
import type { AiAgentOutput, AiAgentRun, AiAgentRunCreateRequest, AiAgentRunPage } from '@/app/lib/types';
import { NowProvider } from '@/app/hooks/useNow';
import { useAskConnexMissions } from '@/app/hooks/useAskConnexMissions';
import { missionPartialDecision, missionProposalOutput } from '@/test/unit/helpers/askConnexMissions';
import { ApiError } from '@/app/lib/api';
import { askConnexToolCardAffordances } from '@/app/lib/askConnex';
import { bindRenderedWorkspace, releaseRenderedWorkspace } from '@/app/lib/renderedWorkspace';

const api = vi.hoisted(() => ({
    getAiAgentRuns: vi.fn<(params: { sessionId?: number }, init: RequestInit) => Promise<AiAgentRunPage>>(),
    createAiAgentRun: vi.fn<(id: number, body: AiAgentRunCreateRequest, init: RequestInit) => Promise<AiAgentRun>>(),
    getAiAgentRun: vi.fn<() => Promise<AiAgentRun>>(),
    getAiAgentOutput: vi.fn<() => Promise<AiAgentOutput>>(),
    controlAiAgentRun: vi.fn<(id: number, action: string, version: string, init: RequestInit) => Promise<AiAgentRun>>(),
    decideAiAgentProposals: vi.fn<() => Promise<import('@/app/lib/types').AiAgentProposalDecisionResult>>(),
    showApiError: vi.fn(),
}));

vi.mock('@/app/hooks/useApiErrorToast', () => ({ useApiErrorToast: () => api.showApiError }));
vi.mock('@/app/lib/api', () => ({
    ...api,
    isWorkspaceUnavailableError: (error: unknown) => error instanceof Error && 'code' in error
        && error.code === 'WORKSPACE_UNAVAILABLE',
    ApiError: class extends Error {
        constructor(message: string, public status: number, public code?: string) { super(message); }
    },
}));

function run(overrides: Partial<AiAgentRun> = {}): AiAgentRun {
    return {
        schemaVersion: 1, id: 12, sessionId: 7, missionType: 'record_review_v1', definitionVersion: 1,
        state: 'queued', reasonCode: null, stateVersion: '1', goal: 'Private review',
        scope: { kind: 'record', recordKind: 'person', recordId: 4 },
        createdAt: '2026-10-15T10:00:00Z', executionDeadlineAt: '2026-10-15T10:15:00Z',
        hardDeadlineAt: '2026-10-15T10:15:00Z', startedAt: null, finishedAt: null,
        outputAvailable: false, outputRevision: '1', proposalMembershipRevision: null, proposalFrozenAt: null,
        proposalFirstPreparedAt: null, reviewExpiresAt: null, proposalCounts: null, contentWithheld: false, availableActions: ['pause', 'cancel'], progress: null,
        ...overrides,
    };
}

function deferred<T>() {
    let resolve: (value: T) => void = () => { throw new Error('Promise not initialized'); };
    const promise = new Promise<T>((finish) => { resolve = finish; });
    return { promise, resolve };
}

async function mount() {
    const interactive = installInteractiveDocument();
    Object.assign(window, {
        setTimeout: globalThis.setTimeout, clearTimeout: globalThis.clearTimeout,
        setInterval: globalThis.setInterval, clearInterval: globalThis.clearInterval,
    });
    const { createRoot } = await import('react-dom/client');
    const root = createRoot(interactive.container);
    let value: ReturnType<typeof useAskConnexMissions> | null = null;
    const changed = vi.fn();
    const invalidated = vi.fn();
    function Harness({ identity, refreshVersion, contentVersion }: { identity: string; refreshVersion: number; contentVersion: number }) {
        value = useAskConnexMissions({ identity, workspaceId: 9, sessionId: 7, enabled: true, visible: true, refreshVersion,
            contentVersion, onChanged: changed, onContentInvalidated: invalidated });
        return null;
    }
    async function render(identity = 'user:workspace', refreshVersion = 0, contentVersion = 0) {
        await act(async () => root.render(<NowProvider value={Date.parse('2026-10-19T12:00:00Z')}><Harness identity={identity} refreshVersion={refreshVersion} contentVersion={contentVersion} /></NowProvider>));
    }
    await render();
    return {
        current: () => { if (value === null) throw new Error('Hook was not rendered'); return value; },
        render, changed, invalidated, unmount: () => act(async () => root.unmount()),
    };
}

describe('Ask Connex durable mission state', () => {
    beforeEach(() => { vi.useFakeTimers(); });
    afterEach(() => { vi.clearAllMocks(); vi.useRealTimers(); vi.unstubAllGlobals(); });

    it.each([{ locale: 'en', messages: enCommon }, { locale: 'ja', messages: jaCommon }])(
        'retains safe cancellation while withholding review content in $locale', ({ locale, messages }) => {
            const withheld = run({ missionType: 'record_preparation_v1', state: 'waiting_approval', contentWithheld: true,
                goal: null, scope: null, availableActions: ['cancel'], proposalCounts: null });
            const markup = renderToStaticMarkup(<NowProvider value={Date.parse('2026-10-19T12:00:00Z')}>
                <NextIntlClientProvider locale={locale} messages={messages} timeZone="UTC">
                    <AskConnexMissionProgress runs={[withheld]} loading={false} problem={null} pendingId={null}
                        output={null} hasMore={false} onControl={() => {}} onOutput={() => {}}
                        onCloseOutput={() => {}} onRetry={() => {}} onMore={() => {}} />
                </NextIntlClientProvider>
            </NowProvider>);
            expect(markup).toContain(messages.AskConnex.missions.controls.cancel);
            expect(markup).not.toContain(messages.AskConnex.missions.controls.pause);
            expect(markup).not.toContain(messages.AskConnex.missions.controls.resume);
            expect(markup).not.toContain('Private review');
        });

    it('invalidates and reloads an open waiting output when its revision changes', async () => {
        const output = missionProposalOutput();
        const available = run({ missionType: 'record_preparation_v1', state: 'waiting_approval', outputAvailable: true,
            outputRevision: '3', proposalMembershipRevision: '2', availableActions: ['cancel', 'view_output'] });
        api.getAiAgentRuns.mockResolvedValue({ items: [available], nextCursor: null });
        api.getAiAgentOutput.mockResolvedValue(output);
        const mounted = await mount();
        await act(async () => mounted.current().viewOutput(available));
        expect(mounted.current().output?.outputRevision).toBe('3');
        const nextOutput = deferred<AiAgentOutput>();
        api.getAiAgentOutput.mockReturnValue(nextOutput.promise);
        api.getAiAgentRuns.mockResolvedValue({ items: [{ ...available, outputRevision: '4', stateVersion: '4' }], nextCursor: null });
        await mounted.render('user:workspace', 1);
        expect(mounted.current().output).toBeNull();
        await act(async () => nextOutput.resolve({ ...output, outputRevision: '4' }));
        expect(mounted.current().output?.outputRevision).toBe('4');
        await mounted.unmount();
    });

    it('reloads canonical cards on invalidation even when the output revision is unchanged', async () => {
        const output = missionProposalOutput();
        const available = run({ missionType: 'record_preparation_v1', state: 'waiting_approval', outputAvailable: true,
            outputRevision: '3', proposalMembershipRevision: '2', availableActions: ['cancel', 'view_output'] });
        api.getAiAgentRuns.mockResolvedValue({ items: [available], nextCursor: null });
        api.getAiAgentOutput.mockResolvedValue(output);
        const mounted = await mount();
        await act(async () => mounted.current().viewOutput(available));
        const changed = missionProposalOutput();
        changed.proposalSet.items[0].card.change = { ...changed.proposalSet.items[0].card.change,
            field: 'owner', currentValue: 'Another owner', currentValueUnresolved: false,
            proposedValue: 'Grace Hopper', state: 'recordChanged' };
        api.getAiAgentOutput.mockResolvedValue(changed);
        await mounted.render('user:workspace', 1);
        expect(api.getAiAgentOutput).toHaveBeenCalledTimes(2);
        expect(mounted.current().output?.proposalSet.items[0].card.change?.state).toBe('recordChanged');
        await mounted.unmount();
    });

    it('keeps output closed when a canonical refresh finishes after dismissal', async () => {
        const output = missionProposalOutput();
        const available = run({ missionType: 'record_preparation_v1', state: 'waiting_approval', outputAvailable: true,
            outputRevision: '3', proposalMembershipRevision: '2', availableActions: ['cancel', 'view_output'] });
        api.getAiAgentRuns.mockResolvedValue({ items: [available], nextCursor: null });
        api.getAiAgentOutput.mockResolvedValue(output);
        const mounted = await mount();
        await act(async () => mounted.current().viewOutput(available));
        const canonical = deferred<AiAgentOutput>();
        api.getAiAgentOutput.mockReturnValueOnce(canonical.promise);
        await mounted.render('user:workspace', 1);
        expect(api.getAiAgentOutput).toHaveBeenCalledTimes(2);
        await act(async () => mounted.current().closeOutput());
        await act(async () => canonical.resolve(output));
        expect(mounted.current().output).toBeNull();
        await mounted.unmount();
    });

    it('keeps output closed when dismissal and a pending list refresh settle together', async () => {
        const output = missionProposalOutput();
        const available = run({ missionType: 'record_preparation_v1', state: 'waiting_approval', outputAvailable: true,
            outputRevision: '3', proposalMembershipRevision: '2', availableActions: ['cancel', 'view_output'] });
        api.getAiAgentRuns.mockResolvedValue({ items: [available], nextCursor: null });
        api.getAiAgentOutput.mockResolvedValue(output);
        const mounted = await mount();
        await act(async () => mounted.current().viewOutput(available));
        const list = deferred<AiAgentRunPage>();
        api.getAiAgentRuns.mockReturnValueOnce(list.promise);
        await mounted.render('user:workspace', 1);
        await act(async () => {
            mounted.current().closeOutput();
            list.resolve({ items: [available], nextCursor: null });
        });
        expect(mounted.current().output).toBeNull();
        await mounted.unmount();
    });

    it('withdraws private output immediately when a successful decision is followed by a withheld canonical read', async () => {
        const output = missionProposalOutput();
        const available = run({ missionType: 'record_preparation_v1', state: 'waiting_approval', outputAvailable: true,
            outputRevision: '3', proposalMembershipRevision: '2', availableActions: ['cancel', 'view_output'] });
        api.getAiAgentRuns.mockResolvedValue({ items: [available], nextCursor: null });
        api.getAiAgentRun.mockResolvedValue({ ...available, stateVersion: '4', outputRevision: '4' });
        api.getAiAgentOutput.mockResolvedValueOnce(output).mockRejectedValue(new ApiError('Withheld', 410, 'mission_output_withheld'));
        api.decideAiAgentProposals.mockResolvedValue(missionPartialDecision());
        const mounted = await mount();
        await act(async () => mounted.current().viewOutput(available));
        const reload = deferred<AiAgentRunPage>();
        api.getAiAgentRuns.mockReturnValue(reload.promise);
        let deciding: Promise<void> | undefined;
        await act(async () => { deciding = mounted.current().decide([31, 32], 'approve'); });
        expect(mounted.current().output).toBeNull();
        expect(mounted.current().decisionResult).toBeNull();
        expect(mounted.current().runs[0]?.goal).toBeNull();
        expect(mounted.invalidated).toHaveBeenCalledWith(7);
        await act(async () => { reload.resolve({ items: [{ ...available, contentWithheld: true, goal: null,
            scope: null, outputAvailable: false, proposalCounts: null }], nextCursor: null }); await deciding; });
        await mounted.unmount();
    });

    it.each(['refused', 'indeterminate'] as const)('preserves earlier committed results and a %s sibling when the post-decision canonical read fails', async (outcome) => {
        const output = missionProposalOutput();
        const available = run({ missionType: 'record_preparation_v1', state: 'waiting_approval', outputAvailable: true,
            outputRevision: '3', proposalMembershipRevision: '2', availableActions: ['cancel', 'view_output'] });
        api.getAiAgentRuns.mockResolvedValue({ items: [available], nextCursor: null });
        api.getAiAgentRun.mockResolvedValue({ ...available, stateVersion: '4', outputRevision: '4' });
        api.getAiAgentOutput.mockResolvedValueOnce(output).mockRejectedValue(new Error('Canonical reload unavailable'));
        const decision = missionPartialDecision(outcome);
        decision.items[1].code = 'access_lost';
        api.decideAiAgentProposals.mockResolvedValue(decision);
        const mounted = await mount();
        await act(async () => mounted.current().viewOutput(available));
        api.getAiAgentRuns.mockResolvedValue({ items: [{ ...available, stateVersion: '4', outputRevision: '4' }], nextCursor: null });
        await act(async () => mounted.current().decide([31, 32], 'approve'));
        expect(mounted.current().decisionResult?.items.map((item) => item.outcome)).toEqual(['executed', outcome]);
        expect(mounted.current().proposalGroup?.cards.map((item) => item.status)).toEqual(['executed', 'proposed']);
        expect(mounted.current().proposalGroup?.cards[1].failure)
            .toBe(outcome === 'refused' ? 'proposalPermissionLost' : 'proposalUnavailable');
        await mounted.unmount();
    });

    it.each(['indeterminate', 'executed', 'rejected'] as const)('retains an earlier %s item when another batch settles and canonical reloads fail', async (outcome) => {
        const output = missionProposalOutput();
        const available = run({ missionType: 'record_preparation_v1', state: 'waiting_approval', outputAvailable: true,
            outputRevision: '3', proposalMembershipRevision: '2', availableActions: ['cancel', 'view_output'] });
        api.getAiAgentRuns.mockResolvedValue({ items: [available], nextCursor: null });
        api.getAiAgentRun.mockRejectedValue(new Error('Canonical reload unavailable'));
        api.getAiAgentOutput.mockResolvedValueOnce(output).mockRejectedValue(new Error('Canonical reload unavailable'));
        const first = { ...missionPartialDecision('indeterminate'), items: [
            { actionSlot: output.proposalSet.items[0].actionSlot, outcome, code: null },
        ] };
        const second = { ...missionPartialDecision(), items: [
            { actionSlot: output.proposalSet.items[1].actionSlot, outcome: 'executed' as const, code: null },
        ] };
        api.decideAiAgentProposals.mockResolvedValueOnce(first);
        const mounted = await mount();
        await act(async () => mounted.current().viewOutput(available));
        await act(async () => mounted.current().decide([31], 'approve'));
        expect(mounted.current().proposalGroup?.cards[1].status).toBe('proposed');
        const response = deferred<import('@/app/lib/types').AiAgentProposalDecisionResult>();
        api.decideAiAgentProposals.mockReturnValueOnce(response.promise);
        let deciding: Promise<void> | undefined;
        await act(async () => { deciding = mounted.current().decide([32], 'approve'); });
        expect(mounted.current().proposalGroup?.cards[0].status).toBe(outcome === 'indeterminate' ? 'proposed' : outcome);
        await act(async () => { response.resolve(second); await deciding; });
        expect(mounted.current().decisionResult?.items.map((item) => item.outcome)).toEqual([outcome, 'executed']);
        const cards = mounted.current().proposalGroup?.cards;
        expect(cards?.map((card) => card.status)).toEqual([outcome === 'indeterminate' ? 'proposed' : outcome, 'executed']);
        expect(cards?.[0].failure).toBe(outcome === 'indeterminate' ? 'proposalUnavailable' : null);
        expect(cards?.map((card) => askConnexToolCardAffordances(card, Date.parse('2026-10-19T12:00:00Z')))).toEqual([[], []]);
        expect(mounted.current().pendingId).toBeNull();
        expect(cards?.map((card) => card.pendingAction)).toEqual([null, null]);
        await act(async () => mounted.current().decide([31, 32], 'approve'));
        await act(async () => mounted.current().decide([31, 32], 'discard'));
        expect(api.decideAiAgentProposals).toHaveBeenCalledTimes(2);
        await mounted.unmount();
    });

    it.each(['refused', 'indeterminate'] as const)('retains earlier uncertainty when a later decision request is %s', async (outcome) => {
        const output = missionProposalOutput();
        const available = run({ missionType: 'record_preparation_v1', state: 'waiting_approval', outputAvailable: true,
            outputRevision: '3', proposalMembershipRevision: '2', availableActions: ['cancel', 'view_output'] });
        api.getAiAgentRuns.mockResolvedValue({ items: [available], nextCursor: null });
        api.getAiAgentRun.mockRejectedValue(new Error('Canonical reload unavailable'));
        api.getAiAgentOutput.mockResolvedValueOnce(output).mockRejectedValue(new Error('Canonical reload unavailable'));
        api.decideAiAgentProposals.mockResolvedValueOnce({ ...missionPartialDecision('indeterminate'),
            items: [{ actionSlot: output.proposalSet.items[0].actionSlot, outcome: 'indeterminate', code: null }] })
            .mockRejectedValueOnce(outcome === 'refused' ? new ApiError('Conflict', 409, 'mission_preparation_conflict')
                : new Error('Decision response unavailable'));
        const mounted = await mount();
        await act(async () => mounted.current().viewOutput(available));
        await act(async () => mounted.current().decide([31], 'approve'));
        await act(async () => mounted.current().decide([32], 'approve'));
        expect(mounted.current().decisionResult?.items.map((item) => item.outcome)).toEqual(['indeterminate', outcome]);
        expect(mounted.current().proposalGroup?.cards[0].failure).toBe('proposalUnavailable');
        expect(mounted.current().pendingId).toBeNull();
        expect(mounted.current().proposalGroup?.cards.map((card) => card.pendingAction)).toEqual([null, null]);
        await act(async () => mounted.current().decide([31], 'discard'));
        expect(api.decideAiAgentProposals).toHaveBeenCalledTimes(2);
        await mounted.unmount();
    });

    it.each(['refused', 'indeterminate'] as const)('settles a %s batch response after realtime advances the output revision', async (outcome) => {
        const output = missionProposalOutput();
        const available = run({ missionType: 'record_preparation_v1', state: 'waiting_approval', outputAvailable: true,
            outputRevision: '3', proposalMembershipRevision: '2', availableActions: ['cancel', 'view_output'] });
        api.getAiAgentRuns.mockResolvedValue({ items: [available], nextCursor: null });
        api.getAiAgentOutput.mockResolvedValue(output);
        const response = deferred<import('@/app/lib/types').AiAgentProposalDecisionResult>();
        api.decideAiAgentProposals.mockReturnValue(response.promise);
        const mounted = await mount();
        await act(async () => mounted.current().viewOutput(available));
        let deciding: Promise<void> | undefined;
        await act(async () => { deciding = mounted.current().decide([31, 32], 'approve'); });
        expect(mounted.current().proposalGroup?.cards.map((card) => card.pendingAction)).toEqual(['approve', 'approve']);
        const refreshed = { ...output, outputRevision: '4', proposalSet: { ...output.proposalSet,
            items: output.proposalSet.items.map((item, index) => index === 0
                ? { ...item, card: { ...item.card, status: 'executed' as const, reviewState: 'applied' as const } } : item) } };
        api.getAiAgentRuns.mockResolvedValue({ items: [{ ...available, stateVersion: '4', outputRevision: '4' }], nextCursor: null });
        api.getAiAgentOutput.mockResolvedValue(refreshed);
        await mounted.render('user:workspace', 1);
        expect(mounted.current().output?.outputRevision).toBe('4');
        api.getAiAgentRun.mockRejectedValue(new Error('Canonical reload unavailable'));
        await act(async () => { response.resolve(missionPartialDecision(outcome)); await deciding; });
        expect(mounted.current().pendingId).toBeNull();
        expect(mounted.current().proposalGroup?.cards.map((card) => card.pendingAction)).toEqual([null, null]);
        expect(mounted.current().decisionResult?.items.map((item) => item.outcome)).toEqual(['executed', outcome]);
        expect(mounted.current().proposalGroup?.cards[1].failure).not.toBeNull();
        await act(async () => mounted.current().decide([32], 'approve'));
        expect(api.decideAiAgentProposals).toHaveBeenCalledOnce();
        await mounted.unmount();
    });

    it('keeps committed results and disarms an indeterminate sibling while canonical reload still shows it pending', async () => {
        vi.setSystemTime(new Date('2026-10-19T12:00:00Z'));
        const output = missionProposalOutput();
        const available = run({ missionType: 'record_preparation_v1', state: 'waiting_approval', outputAvailable: true,
            outputRevision: '3', proposalMembershipRevision: '2', availableActions: ['cancel', 'view_output'] });
        api.getAiAgentRuns.mockResolvedValue({ items: [available], nextCursor: null });
        api.getAiAgentRun.mockResolvedValue({ ...available, stateVersion: '4', outputRevision: '4' });
        api.getAiAgentOutput.mockResolvedValue(output);
        api.getAiAgentOutput.mockResolvedValueOnce(output).mockResolvedValue({ ...output, outputRevision: '4' });
        api.decideAiAgentProposals.mockResolvedValue(missionPartialDecision('indeterminate'));
        const mounted = await mount();
        await act(async () => mounted.current().viewOutput(available));
        await act(async () => mounted.current().decide([31, 32], 'approve'));
        expect(api.decideAiAgentProposals).toHaveBeenCalledOnce();
        expect(mounted.current().decisionResult?.items.map((item) => item.outcome)).toEqual(['executed', 'indeterminate']);
        expect(mounted.current().proposalGroup?.cards[1]?.failure).toBe('proposalUnavailable');
        await act(async () => mounted.current().decide([32], 'approve'));
        expect(api.decideAiAgentProposals).toHaveBeenCalledOnce();
        await mounted.unmount();
    });

    it('retains uncertainty across reopened pending reads and clears only canonically settled preparations', async () => {
        const output = missionProposalOutput();
        const available = run({ missionType: 'record_preparation_v1', state: 'waiting_approval', outputAvailable: true,
            outputRevision: '3', proposalMembershipRevision: '2', availableActions: ['cancel', 'view_output'] });
        api.getAiAgentRuns.mockResolvedValue({ items: [available], nextCursor: null });
        api.getAiAgentRun.mockRejectedValue(new Error('Canonical reload unavailable'));
        api.getAiAgentOutput.mockResolvedValueOnce(output).mockRejectedValue(new Error('Canonical reload unavailable'));
        api.decideAiAgentProposals.mockResolvedValue(missionPartialDecision('indeterminate'));
        const mounted = await mount();
        await act(async () => mounted.current().viewOutput(available));
        await act(async () => mounted.current().decide([31, 32], 'approve'));
        await act(async () => mounted.current().closeOutput());
        api.getAiAgentOutput.mockResolvedValue(missionProposalOutput());
        await act(async () => mounted.current().viewOutput(available));
        expect(mounted.current().decisionResult?.items.map((item) => item.outcome)).toEqual(['executed', 'indeterminate']);
        await act(async () => mounted.current().decide([32], 'discard'));
        expect(api.decideAiAgentProposals).toHaveBeenCalledOnce();
        const partiallySettled = missionProposalOutput();
        partiallySettled.proposalSet.items[0].card.status = 'executed';
        partiallySettled.proposalSet.items[0].card.reviewState = 'applied';
        api.getAiAgentOutput.mockResolvedValue(partiallySettled);
        await mounted.render('user:workspace', 1);
        expect(mounted.current().decisionResult?.items.map((item) => item.outcome)).toEqual(['indeterminate']);
        expect(mounted.current().proposalGroup?.cards[1].failure).toBe('proposalUnavailable');
        const settled = missionProposalOutput();
        settled.proposalSet.items[0].card.status = 'executed';
        settled.proposalSet.items[0].card.reviewState = 'applied';
        settled.proposalSet.items[1].card.status = 'rejected';
        settled.proposalSet.items[1].card.reviewState = 'discarded';
        api.getAiAgentOutput.mockResolvedValue(settled);
        await mounted.render('user:workspace', 2);
        expect(mounted.current().decisionResult).toBeNull();
        expect(mounted.current().proposalGroup?.cards.map((card) => card.status)).toEqual(['executed', 'rejected']);
        expect(mounted.current().proposalGroup?.cards.map((card) => card.failure)).toEqual([null, null]);
        await mounted.unmount();
    });

    it('retains unresolved outcomes when another mission is reviewed and decided', async () => {
        const output = missionProposalOutput();
        const available = run({ missionType: 'record_preparation_v1', state: 'waiting_approval', outputAvailable: true,
            outputRevision: '3', proposalMembershipRevision: '2', availableActions: ['cancel', 'view_output'] });
        const otherRun = { ...available, id: 13 };
        const otherOutput = { ...missionProposalOutput(), runId: 13 };
        api.getAiAgentRuns.mockResolvedValue({ items: [otherRun, available], nextCursor: null });
        api.getAiAgentRun.mockRejectedValue(new Error('Canonical reload unavailable'));
        api.getAiAgentOutput.mockResolvedValueOnce(output).mockRejectedValue(new Error('Canonical reload unavailable'));
        api.decideAiAgentProposals.mockResolvedValue(missionPartialDecision('indeterminate'));
        const mounted = await mount();
        await act(async () => mounted.current().viewOutput(available));
        await act(async () => mounted.current().decide([31, 32], 'approve'));
        await act(async () => mounted.current().closeOutput());
        api.getAiAgentOutput.mockResolvedValue(otherOutput);
        await act(async () => mounted.current().viewOutput(otherRun));
        expect(mounted.current().decisionResult).toBeNull();
        await act(async () => mounted.current().toggleInclusion(31));
        await act(async () => mounted.current().decide([31, 32], 'approve'));
        api.getAiAgentOutput.mockResolvedValue(missionProposalOutput());
        await act(async () => mounted.current().viewOutput(available));
        expect(mounted.current().decisionResult?.items.map((item) => item.outcome)).toEqual(['executed', 'indeterminate']);
        expect(mounted.current().proposalGroup?.cards.map((card) => card.status)).toEqual(['executed', 'proposed']);
        expect(mounted.current().proposalGroup?.cards[1].failure).toBe('proposalUnavailable');
        await act(async () => mounted.current().decide([32], 'approve'));
        expect(api.decideAiAgentProposals).toHaveBeenCalledTimes(2);
        await mounted.unmount();
    });

    it('does not apply a retained outcome to a different preparation in the same slot', async () => {
        const output = missionProposalOutput();
        const available = run({ missionType: 'record_preparation_v1', state: 'waiting_approval', outputAvailable: true,
            outputRevision: '3', proposalMembershipRevision: '2', availableActions: ['cancel', 'view_output'] });
        api.getAiAgentRuns.mockResolvedValue({ items: [available], nextCursor: null });
        api.getAiAgentRun.mockRejectedValue(new Error('Canonical reload unavailable'));
        api.getAiAgentOutput.mockResolvedValueOnce(output).mockRejectedValue(new Error('Canonical reload unavailable'));
        api.decideAiAgentProposals.mockResolvedValue({ ...missionPartialDecision('indeterminate'),
            items: [{ actionSlot: output.proposalSet.items[0].actionSlot, outcome: 'indeterminate', code: null }] });
        const mounted = await mount();
        await act(async () => mounted.current().viewOutput(available));
        await act(async () => mounted.current().decide([31], 'approve'));
        const otherPreparation = missionProposalOutput();
        otherPreparation.proposalSet.items[0].preparationId = 'a4d9f701-b418-4208-8a0a-e727fd4dc949';
        otherPreparation.proposalSet.items[0].card.status = 'executed';
        otherPreparation.proposalSet.items[0].card.reviewState = 'applied';
        api.getAiAgentOutput.mockResolvedValue(otherPreparation);
        await mounted.render('user:workspace', 1);
        expect(mounted.current().decisionResult?.items).toEqual([]);
        expect(mounted.current().proposalGroup?.cards[0].failure).toBeNull();
        api.getAiAgentOutput.mockResolvedValue(missionProposalOutput());
        await mounted.render('user:workspace', 2);
        expect(mounted.current().decisionResult?.items[0].outcome).toBe('indeterminate');
        expect(mounted.current().proposalGroup?.cards[0].failure).toBe('proposalUnavailable');
        await mounted.unmount();
    });

    it('keeps review closed when its pending decision completes', async () => {
        const output = missionProposalOutput();
        const available = run({ missionType: 'record_preparation_v1', state: 'waiting_approval', outputAvailable: true,
            outputRevision: '3', proposalMembershipRevision: '2', availableActions: ['cancel', 'view_output'] });
        api.getAiAgentRuns.mockResolvedValue({ items: [available], nextCursor: null });
        api.getAiAgentOutput.mockResolvedValue(output);
        const response = deferred<import('@/app/lib/types').AiAgentProposalDecisionResult>();
        api.decideAiAgentProposals.mockReturnValue(response.promise);
        const mounted = await mount();
        await act(async () => mounted.current().viewOutput(available));
        let deciding: Promise<void> | undefined;
        await act(async () => { deciding = mounted.current().decide([31, 32], 'approve'); });
        await act(async () => mounted.current().closeOutput());
        api.getAiAgentRun.mockClear();
        await act(async () => { response.resolve(missionPartialDecision()); await deciding; });
        expect(mounted.current().output).toBeNull();
        expect(mounted.current().decisionResult).toBeNull();
        expect(mounted.current().pendingId).toBeNull();
        expect(api.getAiAgentRun).not.toHaveBeenCalled();
        await mounted.unmount();
    });

    it('retains newer realtime output when an older decision reload finishes', async () => {
        const output = missionProposalOutput();
        const available = run({ missionType: 'record_preparation_v1', state: 'waiting_approval', outputAvailable: true,
            outputRevision: '3', proposalMembershipRevision: '2', availableActions: ['cancel', 'view_output'] });
        api.getAiAgentRuns.mockResolvedValue({ items: [available], nextCursor: null });
        const canonical = deferred<AiAgentOutput>();
        const metadata = deferred<AiAgentRun>();
        api.getAiAgentOutput.mockResolvedValueOnce(output).mockReturnValueOnce(canonical.promise)
            .mockResolvedValue({ ...output, outputRevision: '5' });
        api.getAiAgentRun.mockReturnValue(metadata.promise);
        api.decideAiAgentProposals.mockResolvedValue(missionPartialDecision());
        const mounted = await mount();
        await act(async () => mounted.current().viewOutput(available));
        let deciding: Promise<void> | undefined;
        await act(async () => { deciding = mounted.current().decide([31, 32], 'approve'); });
        api.getAiAgentRuns.mockResolvedValue({ items: [{ ...available, stateVersion: '5', outputRevision: '5' }], nextCursor: null });
        await mounted.render('user:workspace', 1);
        expect(mounted.current().output?.outputRevision).toBe('5');
        await act(async () => {
            metadata.resolve({ ...available, stateVersion: '4', outputRevision: '4' });
            canonical.resolve({ ...output, outputRevision: '4' });
            await deciding;
        });
        expect(mounted.current().output?.outputRevision).toBe('5');
        expect(mounted.current().runs[0].stateVersion).toBe('5');
        await mounted.unmount();
    });

    it.each([401, 403, 404, 410])('keeps output withheld when a decision reload finishes after refresh returns %s', async (status) => {
        const output = missionProposalOutput();
        const available = run({ missionType: 'record_preparation_v1', state: 'waiting_approval', outputAvailable: true,
            outputRevision: '3', proposalMembershipRevision: '2', availableActions: ['cancel', 'view_output'] });
        api.getAiAgentRuns.mockResolvedValue({ items: [available], nextCursor: null });
        const canonical = deferred<AiAgentOutput>();
        const metadata = deferred<AiAgentRun>();
        api.getAiAgentOutput.mockResolvedValueOnce(output).mockReturnValueOnce(canonical.promise);
        api.getAiAgentRun.mockReturnValue(metadata.promise);
        api.decideAiAgentProposals.mockResolvedValue(missionPartialDecision());
        const mounted = await mount();
        await act(async () => mounted.current().viewOutput(available));
        let deciding: Promise<void> | undefined;
        await act(async () => { deciding = mounted.current().decide([31, 32], 'approve'); });
        api.getAiAgentRuns.mockRejectedValue(new ApiError('Access lost', status));
        await mounted.render('user:workspace', 1);
        expect(mounted.current().output).toBeNull();
        const problem = mounted.current().problem;
        expect(problem).not.toBeNull();
        expect(mounted.invalidated).toHaveBeenCalledWith(7);
        await act(async () => {
            metadata.resolve({ ...available, stateVersion: '4', outputRevision: '4' });
            canonical.resolve({ ...output, outputRevision: '4' });
            await deciding;
        });
        expect(mounted.current().output).toBeNull();
        expect(mounted.current().runs).toEqual([]);
        expect(mounted.current().decisionResult).toBeNull();
        expect(mounted.current().pendingId).toBeNull();
        expect(mounted.current().problem).toBe(problem);
        await mounted.unmount();
    });

    it('does not poll an idle session when its parent rerenders', async () => {
        api.getAiAgentRuns.mockResolvedValue({ items: [], nextCursor: null });
        const mounted = await mount();
        for (let index = 0; index < 20; index++) await mounted.render();
        await act(async () => { await vi.advanceTimersByTimeAsync(60_000); });
        expect(api.getAiAgentRuns).toHaveBeenCalledTimes(1);
        await mounted.unmount();
    });

    it.each(['paused', 'waiting_approval', 'done', 'failed', 'cancelled'] as const)(
        'does not poll a %s mission', async (state) => {
            api.getAiAgentRuns.mockResolvedValue({ items: [run({ state })], nextCursor: null });
            const mounted = await mount();
            await act(async () => { await vi.advanceTimersByTimeAsync(60_000); });
            expect(api.getAiAgentRuns).toHaveBeenCalledTimes(1);
            await mounted.unmount();
        });

    it('backs off unchanged active missions and resets the delay when progress changes', async () => {
        api.getAiAgentRuns.mockResolvedValue({ items: [run()], nextCursor: null });
        const mounted = await mount();
        for (const [index, delay] of [5_000, 10_000, 20_000, 30_000, 30_000].entries()) {
            await act(async () => { await vi.advanceTimersByTimeAsync(delay - 1); });
            expect(api.getAiAgentRuns).toHaveBeenCalledTimes(index + 1);
            await act(async () => { await vi.advanceTimersByTimeAsync(1); });
            expect(api.getAiAgentRuns).toHaveBeenCalledTimes(index + 2);
        }
        api.getAiAgentRuns.mockResolvedValue({ items: [run({ state: 'running', stateVersion: '2' })], nextCursor: null });
        await act(async () => { await vi.advanceTimersByTimeAsync(30_000); });
        expect(api.getAiAgentRuns).toHaveBeenCalledTimes(7);
        await act(async () => { await vi.advanceTimersByTimeAsync(4_999); });
        expect(api.getAiAgentRuns).toHaveBeenCalledTimes(7);
        await act(async () => { await vi.advanceTimersByTimeAsync(1); });
        expect(api.getAiAgentRuns).toHaveBeenCalledTimes(8);
        await mounted.unmount();
    });

    it('waits for each poll response and stops when the mission settles', async () => {
        const pending = deferred<AiAgentRunPage>();
        api.getAiAgentRuns.mockResolvedValueOnce({ items: [run()], nextCursor: null })
            .mockReturnValue(pending.promise);
        const mounted = await mount();
        await act(async () => { await vi.advanceTimersByTimeAsync(60_000); });
        expect(api.getAiAgentRuns).toHaveBeenCalledTimes(2);
        await act(async () => pending.resolve({ items: [run({ state: 'done', stateVersion: '2' })], nextCursor: null }));
        await act(async () => { await vi.advanceTimersByTimeAsync(60_000); });
        expect(api.getAiAgentRuns).toHaveBeenCalledTimes(2);
        await mounted.unmount();
    });

    it('refreshes from realtime while idle and cancels the pending poll when realtime settles a mission', async () => {
        api.getAiAgentRuns.mockResolvedValueOnce({ items: [], nextCursor: null })
            .mockResolvedValueOnce({ items: [run()], nextCursor: null })
            .mockResolvedValue({ items: [run({ state: 'done', stateVersion: '2' })], nextCursor: null });
        const mounted = await mount();
        await mounted.render('user:workspace', 1);
        expect(api.getAiAgentRuns).toHaveBeenCalledTimes(2);
        await act(async () => { await vi.advanceTimersByTimeAsync(1_000); });
        await mounted.render('user:workspace', 2);
        expect(api.getAiAgentRuns).toHaveBeenCalledTimes(3);
        await act(async () => { await vi.advanceTimersByTimeAsync(60_000); });
        expect(api.getAiAgentRuns).toHaveBeenCalledTimes(3);
        expect(mounted.current().runs[0]?.state).toBe('done');
        await mounted.unmount();
    });

    it('withdraws Resume when realtime reports a paused mission expiry', async () => {
        api.getAiAgentRuns.mockResolvedValueOnce({ items: [run({ state: 'paused', availableActions: ['resume', 'cancel'] })], nextCursor: null })
            .mockResolvedValue({ items: [run({ state: 'failed', stateVersion: '2', reasonCode: 'expired', availableActions: [] })], nextCursor: null });
        const mounted = await mount();
        expect(mounted.current().runs[0]?.availableActions).toContain('resume');
        await act(async () => { await vi.advanceTimersByTimeAsync(60_000); });
        expect(api.getAiAgentRuns).toHaveBeenCalledTimes(1);

        await mounted.render('user:workspace', 1);
        expect(api.getAiAgentRuns).toHaveBeenCalledTimes(2);
        expect(mounted.current().runs[0]?.state).toBe('failed');
        expect(mounted.current().runs[0]?.reasonCode).toBe('expired');
        expect(mounted.current().runs[0]?.availableActions).not.toContain('resume');
        await act(async () => { await vi.advanceTimersByTimeAsync(60_000); });
        expect(api.getAiAgentRuns).toHaveBeenCalledTimes(2);
        await mounted.unmount();
    });

    it('stops polling on a failed read and allows explicit recovery', async () => {
        api.getAiAgentRuns.mockResolvedValueOnce({ items: [run()], nextCursor: null })
            .mockRejectedValueOnce(new Error('Connection interrupted'))
            .mockResolvedValue({ items: [], nextCursor: null });
        const mounted = await mount();
        await act(async () => { await vi.advanceTimersByTimeAsync(5_000); });
        expect(mounted.current().problem).toBe('failed');
        await act(async () => { await vi.advanceTimersByTimeAsync(60_000); });
        expect(api.getAiAgentRuns).toHaveBeenCalledTimes(2);
        await act(async () => mounted.current().retry());
        expect(api.getAiAgentRuns).toHaveBeenCalledTimes(3);
        expect(mounted.current().problem).toBeNull();
        await mounted.unmount();
    });

    it('discovers durable missions without a realtime frame or tool-call history', async () => {
        api.getAiAgentRuns.mockResolvedValue({ items: [run()], nextCursor: null });
        const mounted = await mount();
        expect(mounted.current().runs.map((item) => item.id)).toEqual([12]);
        expect(api.getAiAgentRuns.mock.calls[0]?.[0]).toEqual({ sessionId: 7, limit: 20 });
        await mounted.unmount();
    });

    it('discards a late list response after a workspace or account switch', async () => {
        const old = deferred<AiAgentRunPage>();
        api.getAiAgentRuns.mockReturnValueOnce(old.promise).mockResolvedValue({ items: [], nextCursor: null });
        const mounted = await mount();
        const oldSignal = api.getAiAgentRuns.mock.calls[0]?.[1].signal;
        await mounted.render('new-user:new-workspace');
        await act(async () => old.resolve({ items: [run()], nextCursor: null }));
        expect(oldSignal?.aborted).toBe(true);
        expect(mounted.current().runs).toEqual([]);
        await mounted.unmount();
    });

    it('keeps a stable creation key after an uncertain response and prevents duplicate submissions', async () => {
        api.getAiAgentRuns.mockResolvedValue({ items: [], nextCursor: null });
        const response = deferred<AiAgentRun>();
        api.createAiAgentRun.mockRejectedValueOnce(new Error('Connection interrupted')).mockReturnValueOnce(response.promise);
        const mounted = await mount();
        const scope = { kind: 'record', recordKind: 'person', recordId: 4 } satisfies AiAgentRunCreateRequest['scope'];
        await act(async () => { await mounted.current().create(7, 'Review', scope); });
        let retry: Promise<boolean> | undefined;
        await act(async () => {
            retry = mounted.current().create(7, 'Review', scope);
            expect(await mounted.current().create(7, 'Review', scope)).toBe(false);
        });
        expect(api.createAiAgentRun).toHaveBeenCalledTimes(2);
        expect(api.createAiAgentRun.mock.calls[0]?.[1].clientRequestKey)
            .toBe(api.createAiAgentRun.mock.calls[1]?.[1].clientRequestKey);
        await act(async () => { response.resolve(run()); await retry; });
        expect(mounted.current().runs).toHaveLength(1);
        await mounted.unmount();
    });

    it('pins delayed creation and controls to their content workspace after the shell selection changes', async () => {
        api.getAiAgentRuns.mockResolvedValue({ items: [run()], nextCursor: null });
        api.createAiAgentRun.mockResolvedValue(run());
        api.controlAiAgentRun.mockResolvedValue(run({ state: 'paused', stateVersion: '2' }));
        const mounted = await mount();
        const shell = {};
        try {
            const create = mounted.current().create;
            const control = mounted.current().control;
            bindRenderedWorkspace(shell, 5);
            await act(async () => { await create(7, 'Review', { kind: 'record', recordKind: 'person', recordId: 4 }); });
            await act(async () => { await control(run(), 'pause'); });
            expect(new Headers(api.createAiAgentRun.mock.calls[0]?.[2].headers).get('X-Workspace-Id')).toBe('9');
            expect(new Headers(api.controlAiAgentRun.mock.calls[0]?.[3].headers).get('X-Workspace-Id')).toBe('9');
        } finally {
            releaseRenderedWorkspace(shell);
            await mounted.unmount();
        }
    });

    it.each(['create', 'control'] as const)('reports a refused %s workspace without resubmitting the mutation', async (action) => {
        api.getAiAgentRuns.mockResolvedValue({ items: [run()], nextCursor: null });
        const error = new ApiError('Not a member of workspace 9', 403, 'WORKSPACE_UNAVAILABLE');
        const mounted = await mount();
        try {
            if (action === 'create') {
                api.createAiAgentRun.mockRejectedValueOnce(error);
                await act(async () => { await mounted.current().create(7, 'Review', { kind: 'record', recordKind: 'person', recordId: 4 }); });
                expect(api.createAiAgentRun).toHaveBeenCalledOnce();
            } else {
                api.controlAiAgentRun.mockRejectedValueOnce(error);
                await act(async () => { await mounted.current().control(run(), 'pause'); });
                expect(api.controlAiAgentRun).toHaveBeenCalledOnce();
            }
            expect(api.showApiError).toHaveBeenCalledExactlyOnceWith(error);
        } finally {
            await mounted.unmount();
        }
    });

    it('does not restore a suspended output response after privacy withholding is refreshed', async () => {
        const available = run({ state: 'done', stateVersion: '3', outputAvailable: true, availableActions: ['view_output'] });
        api.getAiAgentRuns.mockResolvedValueOnce({ items: [available], nextCursor: null })
            .mockResolvedValue({ items: [run({ state: 'done', stateVersion: '4', goal: null, scope: null,
                contentWithheld: true, availableActions: [] })], nextCursor: null });
        const response = deferred<AiAgentOutput>();
        api.getAiAgentOutput.mockReturnValue(response.promise);
        const mounted = await mount();
        let viewing: Promise<void> | undefined;
        await act(async () => { viewing = mounted.current().viewOutput(available); });
        await mounted.render('user:workspace', 1);
        await act(async () => {
            response.resolve({ schemaVersion: 1, runId: 12, outputRevision: '1', report: {
                messageId: 10, markdown: 'Private report', citations: [], generatedAt: '2026-10-15T10:00:00Z',
                coverage: { processed: 1, skipped: 0, failed: 0, unprocessed: 0 },
            }, proposalSet: { id: 12, revision: '0', frozen: true, frozenAt: null, firstPreparedAt: null, reviewExpiresAt: null, items: [],
                derivedCounts: { pending: 0, executed: 0, rejected: 0, withdrawn: 0, undone: 0 } }, autoWriteReceipts: [] });
            await viewing;
        });
        expect(mounted.current().output).toBeNull();
        expect(mounted.current().runs[0]?.goal).toBeNull();
        expect(mounted.invalidated).toHaveBeenCalledExactlyOnceWith(7);
        await mounted.unmount();
    });

    it('withdraws rendered reports immediately while the post-purge reload is suspended', async () => {
        const available = run({ state: 'done', outputAvailable: true, availableActions: ['view_output'] });
        const output: AiAgentOutput = {
            schemaVersion: 1, runId: 12, outputRevision: '1', report: {
                messageId: 10, markdown: 'Private report', citations: [], generatedAt: '2026-10-15T10:00:00Z',
                coverage: { processed: 1, skipped: 0, failed: 0, unprocessed: 0 },
            }, proposalSet: { id: 12, revision: '0', frozen: true, frozenAt: null, firstPreparedAt: null, reviewExpiresAt: null, items: [],
                derivedCounts: { pending: 0, executed: 0, rejected: 0, withdrawn: 0, undone: 0 } }, autoWriteReceipts: [],
        };
        api.getAiAgentRuns.mockResolvedValue({ items: [available], nextCursor: null });
        api.getAiAgentOutput.mockResolvedValue(output);
        const mounted = await mount();
        await act(async () => { await mounted.current().viewOutput(available); });
        expect(mounted.current().output?.report.markdown).toBe('Private report');

        const reload = deferred<AiAgentRunPage>();
        api.getAiAgentRuns.mockReturnValue(reload.promise);
        await mounted.render('user:workspace', 1, 1);
        expect(mounted.current().output).toBeNull();
        expect(mounted.current().runs).toEqual([]);

        await act(async () => reload.resolve({ items: [], nextCursor: null }));
        expect(mounted.current().output).toBeNull();
        await mounted.unmount();
    });

    it('rejects pre-invalidation output and list responses during reconnect cleanup', async () => {
        const available = run({ state: 'done', outputAvailable: true, availableActions: ['view_output'] });
        api.getAiAgentRuns.mockResolvedValueOnce({ items: [available], nextCursor: null });
        const mounted = await mount();
        const oldList = deferred<AiAgentRunPage>();
        const oldOutput = deferred<AiAgentOutput>();
        api.getAiAgentRuns.mockReturnValueOnce(oldList.promise)
            .mockResolvedValue({ items: [], nextCursor: null });
        api.getAiAgentOutput.mockReturnValue(oldOutput.promise);
        await mounted.render('user:workspace', 1);
        let viewing: Promise<void> | undefined;
        await act(async () => { viewing = mounted.current().viewOutput(available); });

        const oldSignal = api.getAiAgentRuns.mock.calls.at(-1)?.[1].signal;
        await mounted.render('user:workspace', 2, 1);
        expect(oldSignal?.aborted).toBe(true);
        await act(async () => {
            oldList.resolve({ items: [available], nextCursor: null });
            oldOutput.resolve({ schemaVersion: 1, runId: 12, outputRevision: '1', report: {
                messageId: 10, markdown: 'Withdrawn report', citations: [], generatedAt: '2026-10-15T10:00:00Z',
                coverage: { processed: 1, skipped: 0, failed: 0, unprocessed: 0 },
            }, proposalSet: { id: 12, revision: '0', frozen: true, frozenAt: null, firstPreparedAt: null, reviewExpiresAt: null, items: [],
                derivedCounts: { pending: 0, executed: 0, rejected: 0, withdrawn: 0, undone: 0 } }, autoWriteReceipts: [] });
            await viewing;
        });
        expect(mounted.current().runs).toEqual([]);
        expect(mounted.current().output).toBeNull();
        await mounted.unmount();
    });

    it('does not infer a purge when older missions leave the loaded pagination window', async () => {
        api.getAiAgentRuns.mockResolvedValueOnce({ items: [run({ id: 12 })], nextCursor: 'older' })
            .mockResolvedValueOnce({ items: [run({ id: 13 })], nextCursor: 'older' })
            .mockResolvedValue({ items: [run({ id: 13 })], nextCursor: null });
        const mounted = await mount();
        await mounted.render('user:workspace', 1);
        expect(mounted.invalidated).not.toHaveBeenCalled();
        await mounted.render('user:workspace', 2);
        expect(mounted.invalidated).not.toHaveBeenCalled();
        await mounted.unmount();
    });

    it('withdraws session content when a known mission disappears within the reloaded range', async () => {
        api.getAiAgentRuns.mockResolvedValueOnce({ items: [run({ id: 14 }), run({ id: 12 })], nextCursor: 'older' })
            .mockResolvedValue({ items: [run({ id: 12 })], nextCursor: 'older' });
        const mounted = await mount();
        await mounted.render('user:workspace', 1);
        expect(mounted.invalidated).toHaveBeenCalledExactlyOnceWith(7);
        await mounted.unmount();
    });

    it('withdraws session content when a control response observes privacy withholding', async () => {
        const available = run();
        api.getAiAgentRuns.mockResolvedValue({ items: [available], nextCursor: null });
        api.controlAiAgentRun.mockResolvedValue(run({ stateVersion: '2', contentWithheld: true,
            goal: null, scope: null, availableActions: [] }));
        const mounted = await mount();
        await act(async () => { await mounted.current().control(available, 'pause'); });
        expect(mounted.invalidated).toHaveBeenCalledExactlyOnceWith(7);
        expect(mounted.current().runs[0]?.goal).toBeNull();
        await mounted.unmount();
    });
});
