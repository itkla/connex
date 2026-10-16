import { act } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { installInteractiveDocument } from '@/test/unit/helpers/interactiveDocument';
import type { AiAgentOutput, AiAgentRun, AiAgentRunCreateRequest, AiAgentRunPage } from '@/app/lib/types';
import { useAskConnexMissions } from '@/app/hooks/useAskConnexMissions';

const api = vi.hoisted(() => ({
    getAiAgentRuns: vi.fn<(params: { sessionId?: number }, init: RequestInit) => Promise<AiAgentRunPage>>(),
    createAiAgentRun: vi.fn<(id: number, body: AiAgentRunCreateRequest, init: RequestInit) => Promise<AiAgentRun>>(),
    getAiAgentRun: vi.fn<() => Promise<AiAgentRun>>(),
    getAiAgentOutput: vi.fn<() => Promise<AiAgentOutput>>(),
    controlAiAgentRun: vi.fn<() => Promise<AiAgentRun>>(),
}));

vi.mock('@/app/lib/api', () => ({
    ...api,
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
        outputAvailable: false, contentWithheld: false, availableActions: ['pause', 'cancel'], progress: null,
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
    Object.assign(window, { setInterval: vi.fn(() => 1), clearInterval: vi.fn() });
    const { createRoot } = await import('react-dom/client');
    const root = createRoot(interactive.container);
    let value: ReturnType<typeof useAskConnexMissions> | null = null;
    const changed = vi.fn();
    const invalidated = vi.fn();
    function Harness({ identity, refreshVersion, contentVersion }: { identity: string; refreshVersion: number; contentVersion: number }) {
        value = useAskConnexMissions({ identity, sessionId: 7, enabled: true, visible: true, refreshVersion,
            contentVersion, onChanged: changed, onContentInvalidated: invalidated });
        return null;
    }
    async function render(identity = 'user:workspace', refreshVersion = 0, contentVersion = 0) {
        await act(async () => root.render(<Harness identity={identity} refreshVersion={refreshVersion} contentVersion={contentVersion} />));
    }
    await render();
    return {
        current: () => { if (value === null) throw new Error('Hook was not rendered'); return value; },
        render, changed, invalidated, unmount: () => act(async () => root.unmount()),
    };
}

describe('Ask Connex durable mission state', () => {
    afterEach(() => { vi.clearAllMocks(); vi.unstubAllGlobals(); });

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
            }, proposalSet: { id: 12, revision: '0', frozen: true, reviewExpiresAt: null, items: [],
                derivedCounts: { pending: 0, executed: 0, rejected: 0, withdrawn: 0 } }, autoWriteReceipts: [] });
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
            }, proposalSet: { id: 12, revision: '0', frozen: true, reviewExpiresAt: null, items: [],
                derivedCounts: { pending: 0, executed: 0, rejected: 0, withdrawn: 0 } }, autoWriteReceipts: [],
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
            }, proposalSet: { id: 12, revision: '0', frozen: true, reviewExpiresAt: null, items: [],
                derivedCounts: { pending: 0, executed: 0, rejected: 0, withdrawn: 0 } }, autoWriteReceipts: [] });
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
