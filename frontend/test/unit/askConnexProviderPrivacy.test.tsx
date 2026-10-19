import { act, isValidElement, type ComponentProps } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import AskConnexProvider from '@/app/components/ask-connex/AskConnexProvider';
import type AskConnexDrawer from '@/app/components/ask-connex/AskConnexDrawer';
import type AskConnexMissionProgress from '@/app/components/ask-connex/AskConnexMissionProgress';
import { ASK_CONNEX_REALTIME_REFRESH_DELAY_MS } from '@/app/components/ask-connex/realtimeRefreshCoalescer';
import { ApiError } from '@/app/lib/api';
import type { AiChatSocketHandlers } from '@/app/lib/realtime';
import type { AiAgentRun, AiAgentRunPage, AiChatSession, AiChatSessionDetail, AiChatTurn } from '@/app/lib/types';
import { installInteractiveDocument } from '@/test/unit/helpers/interactiveDocument';

const harness = vi.hoisted(() => ({
    drawer: vi.fn<(props: ComponentProps<typeof AskConnexDrawer>) => null>(() => null),
    socket: vi.fn<(handlers: AiChatSocketHandlers) => { activate: () => void; deactivate: () => void }>(
        () => ({ activate: () => {}, deactivate: () => {} })),
    getSession: vi.fn<() => Promise<AiChatSessionDetail>>(),
    getTurn: vi.fn<() => Promise<AiChatTurn>>(),
    getMissions: vi.fn<() => Promise<AiAgentRunPage>>(),
    translate: (key: string) => key,
    persist: () => true,
    toast: vi.fn(),
    router: { push: vi.fn(), replace: vi.fn(), back: vi.fn() },
    context: { user: { id: 1, displayName: 'Member' }, record: null, selection: null },
}));

vi.mock('@/app/components/ask-connex/AskConnexDrawer', () => ({ default: harness.drawer }));
vi.mock('@/app/components/ask-connex/AskConnexMissionCreate', () => ({ default: () => null }));
vi.mock('@/app/components/ask-connex/AskConnexMissionProgress', () => ({ default: () => null }));
vi.mock('next-intl', () => ({ useLocale: () => 'en', useTranslations: () => harness.translate }));
vi.mock('next/navigation', () => ({ useParams: () => ({ sessionId: '7' }), usePathname: () => '/ask-connex/7', useRouter: () => harness.router }));
vi.mock('@/app/hooks/useActions', () => ({ useActions: () => ({ context: harness.context }), useRegisterActions: () => {} }));
vi.mock('@/app/hooks/useWorkspace', () => ({ useWorkspace: () => ({ activeWorkspaceId: 1, switching: false }) }));
vi.mock('@/app/hooks/usePermissions', () => ({ usePermissionCheck: () => 'granted' }));
vi.mock('@/app/hooks/useAskConnexSkills', () => ({ useAskConnexSkills: () => [] }));
vi.mock('@/app/hooks/useApiErrorToast', () => ({ useApiErrorToast: () => harness.toast }));
vi.mock('@/app/hooks/useIsMobile', () => ({ useIsMobile: () => false }));
vi.mock('@/app/hooks/useNow', () => ({ useLiveNow: () => 0 }));
vi.mock('@/app/hooks/useAccountStorageGeneration', () => ({ useAccountStorageGeneration: () => harness.persist }));
vi.mock('@/app/lib/realtime', () => ({ createAiChatSocket: harness.socket }));
vi.mock('@/app/lib/api', async (importOriginal) => ({
    ...await importOriginal<typeof import('@/app/lib/api')>(),
    getAiChatSessions: async () => ({ items: [] }),
    getAiChatInvitations: async () => ({ items: [] }),
    getActiveWorkspaceMembers: async () => [],
    getAiChatSession: harness.getSession,
    getAiChatTurn: harness.getTurn,
    getAiChatAttachments: async () => [],
    getAiAssistantToolCalls: async () => [],
    getAiAgentRuns: harness.getMissions,
}));

const session: AiChatSession = {
    id: 7, workspaceId: 1, createdByUserId: 1, title: 'Ask Connex', visibility: 'private',
    status: 'active', archived: false, ownedByCurrentUser: true, historySummarized: false,
    participationStatus: null, lastMessageAt: null, archivedAt: null,
    createdAt: '2026-10-15T10:00:00Z', updatedAt: '2026-10-15T10:00:00Z',
};

function detail(activeTurn: AiChatTurn | null): AiChatSessionDetail {
    return { session, activeTurn, messages: { items: [], total: 0 } };
}

function mission(overrides: Partial<AiAgentRun> = {}): AiAgentRun {
    return {
        schemaVersion: 1, id: 12, sessionId: 7, missionType: 'record_review_v1', definitionVersion: 1,
        state: 'done', reasonCode: null, stateVersion: '3', goal: 'Private goal',
        scope: { kind: 'record', recordKind: 'person', recordId: 4 },
        createdAt: '2026-10-15T10:00:00Z', executionDeadlineAt: '2026-10-15T10:15:00Z',
        hardDeadlineAt: '2026-10-15T10:15:00Z', startedAt: null, finishedAt: null,
        outputAvailable: true, contentWithheld: false, availableActions: ['view_output'], progress: null,
        ...overrides,
    };
}

function props() {
    const value = harness.drawer.mock.calls.at(-1)?.[0];
    if (!value) throw new Error('Provider has not rendered');
    return value;
}

function socket() {
    const value = harness.socket.mock.calls.at(-1)?.[0];
    if (!value) throw new Error('Socket has not connected');
    return value;
}

function missionProps() {
    const progress = props().missionProgress;
    if (!isValidElement<ComponentProps<typeof AskConnexMissionProgress>>(progress)) {
        throw new Error('Provider has not rendered mission progress');
    }
    return progress.props;
}

async function flushRealtimeRefresh() {
    await act(async () => { await vi.advanceTimersByTimeAsync(ASK_CONNEX_REALTIME_REFRESH_DELAY_MS); });
}

async function mount(active = true) {
    const interactive = installInteractiveDocument();
    Object.assign(window, {
        setTimeout: globalThis.setTimeout, clearTimeout: globalThis.clearTimeout,
        setInterval: globalThis.setInterval, clearInterval: globalThis.clearInterval,
        requestAnimationFrame: (callback: FrameRequestCallback) => window.setTimeout(() => callback(0), 0),
        cancelAnimationFrame: (handle: number) => window.clearTimeout(handle),
        localStorage: { getItem: () => null, setItem: () => {}, removeItem: () => {} },
    });
    const running: AiChatTurn = { turnId: 9, sessionId: 7, status: 'running', terminalReason: null, progress: [] };
    harness.getSession.mockResolvedValue(detail(active ? running : null));
    harness.getTurn.mockResolvedValue(running);
    const { createRoot } = await import('react-dom/client');
    const root = createRoot(interactive.container);
    const render = () => act(async () => root.render(<AskConnexProvider>{null}</AskConnexProvider>));
    await render();
    return { render, unmount: () => act(async () => root.unmount()) };
}

async function stream(text: string, seq = 0) {
    await act(async () => {
        socket().onDelta?.({ workspaceId: 1, sessionId: 7, turnId: 9, kind: 'delta', seq, text });
        await vi.advanceTimersByTimeAsync(0);
    });
}

describe('Ask Connex provider privacy withdrawal', () => {
    beforeEach(() => {
        vi.useFakeTimers();
        vi.clearAllMocks();
        harness.getMissions.mockResolvedValue({ items: [], nextCursor: null });
    });
    afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); });

    it.each(['mission_created', 'mission_state', 'mission_output'] as const)(
        'keeps an idle session quiet through provider rerenders and resumes discovery on %s', async (kind) => {
        const mounted = await mount(false);
        const initialRequests = harness.getMissions.mock.calls.length;
        expect(initialRequests).toBeGreaterThan(0);
        for (let index = 0; index < 3; index++) await mounted.render();
        await act(async () => { await vi.advanceTimersByTimeAsync(60_000); });
        expect(harness.getMissions).toHaveBeenCalledTimes(initialRequests);

        await act(async () => socket().onMissionFrame?.({ workspaceId: 1, sessionId: 7,
            runId: 12, stateVersion: '1', kind, coarseState: 'queued' }));
        expect(harness.getMissions.mock.calls.length).toBeGreaterThan(initialRequests);
        await flushRealtimeRefresh();
        const refreshedRequests = harness.getMissions.mock.calls.length;
        await act(async () => { await vi.advanceTimersByTimeAsync(60_000); });
        expect(harness.getMissions).toHaveBeenCalledTimes(refreshedRequests);
        await mounted.unmount();
    });

    it('withdraws Resume for an expired paused mission within a mixed metadata burst', async () => {
        harness.getMissions.mockResolvedValue({ items: [mission({
            state: 'paused', stateVersion: '1', availableActions: ['resume', 'cancel'],
        })], nextCursor: null });
        const mounted = await mount(false);
        try {
            expect(missionProps().runs[0]?.availableActions).toContain('resume');
            harness.getMissions.mockClear();
            harness.getSession.mockClear();
            harness.getMissions.mockResolvedValue({ items: [mission({
                state: 'failed', stateVersion: '2', reasonCode: 'expired', availableActions: [],
            })], nextCursor: null });
            await act(async () => {
                for (let seq = 0; seq < 48; seq++) {
                    socket().onFrame({ workspaceId: 1, sessionId: seq % 2 === 0 ? 7 : 8, turnId: 9,
                        kind: 'step', seq, tool: null, status: 'running', reason: null });
                }
                socket().onMissionFrame?.({ workspaceId: 1, sessionId: 7, runId: 12,
                    stateVersion: '2', kind: 'mission_state', coarseState: 'failed' });
            });
            expect(harness.getMissions).toHaveBeenCalledOnce();
            expect(missionProps().runs[0]).toMatchObject({ state: 'failed', reasonCode: 'expired', availableActions: [] });
            expect(harness.getSession).not.toHaveBeenCalled();
            await flushRealtimeRefresh();
            expect(harness.getSession).toHaveBeenCalledOnce();
            await flushRealtimeRefresh();
            expect(harness.getSession).toHaveBeenCalledOnce();
        } finally {
            await mounted.unmount();
        }
    });

    it('withdraws mission content during an in-flight coalesced refresh and ignores its stale response', async () => {
        harness.getMissions.mockResolvedValue({ items: [mission()], nextCursor: null });
        const mounted = await mount();
        const suspended = Promise.withResolvers<AiChatSessionDetail>();
        try {
            await stream('Withdrawn mission content');
            harness.getSession.mockClear().mockResolvedValue(detail(null)).mockReturnValueOnce(suspended.promise);
            await act(async () => {
                for (const sessionId of [7, 8]) {
                    socket().onFrame({ workspaceId: 1, sessionId, turnId: 9,
                        kind: 'step', seq: 0, tool: null, status: 'running', reason: null });
                }
            });
            await flushRealtimeRefresh();
            expect(harness.getSession).toHaveBeenCalledOnce();
            expect(props().streamStore.getSnapshot()?.text).toBe('Withdrawn mission content');
            harness.getMissions.mockResolvedValue({ items: [mission({
                stateVersion: '4', contentWithheld: true, goal: null, outputAvailable: false, availableActions: [],
            })], nextCursor: null });
            await act(async () => socket().onMissionFrame?.({ workspaceId: 1, sessionId: 7, runId: 12,
                stateVersion: '4', kind: 'mission_state', coarseState: 'done' }));
            expect(props().streamStore.getSnapshot()).toBeNull();
            expect(props().messages).toEqual([]);
            expect(props().continuePrompt).toBeNull();
            await act(async () => suspended.resolve(detail({
                turnId: 9, sessionId: 7, status: 'running', terminalReason: null, progress: [],
            })));
            await stream('Late withdrawn mission content');
            expect(props().streamStore.getSnapshot()).toBeNull();
        } finally {
            suspended.resolve(detail(null));
            await mounted.unmount();
        }
    });

    it('discards buffered and rendered text on a privacy frame before a suspended transcript refresh', async () => {
        const mounted = await mount();
        await stream('Withdrawn text');
        expect(props().streamStore.getSnapshot()?.text).toBe('Withdrawn text');
        await stream('Buffered withdrawn text', 50);
        await act(async () => socket().onFrame({ workspaceId: 1, sessionId: 8, turnId: 10,
            kind: 'state', seq: 0, tool: null, status: 'cancelled', reason: 'privacy_invalidated' }));
        expect(props().streamStore.getSnapshot()?.text).toBe('Withdrawn text');
        harness.getSession.mockReturnValue(new Promise(() => {}));

        await act(async () => socket().onFrame({ workspaceId: 1, sessionId: 7, turnId: 9,
            kind: 'state', seq: 0, tool: null, status: 'cancelled', reason: 'privacy_invalidated' }));

        expect(props().streamStore.getSnapshot()).toBeNull();
        expect(props().streaming).toBe(false);
        expect(props().messages).toEqual([]);
        expect(props().continuePrompt).toBeNull();
        await stream('Late withdrawn text');
        expect(props().streamStore.getSnapshot()).toBeNull();
        await mounted.unmount();
    });

    it('withdraws a purged partial from canonical polling without a realtime signal', async () => {
        const mounted = await mount();
        await stream('Withdrawn text');
        expect(props().streamStore.getSnapshot()?.text).toBe('Withdrawn text');
        harness.getTurn.mockResolvedValue({ turnId: 9, sessionId: 7, status: 'cancelled',
            terminalReason: 'privacy_invalidated', partialContent: '', progress: [] });
        harness.getSession.mockResolvedValue(detail(null));
        await act(async () => { await vi.advanceTimersByTimeAsync(1_000); });

        expect(props().streamStore.getSnapshot()).toBeNull();
        expect(props().streaming).toBe(false);
        expect(props().continuePrompt).toBeNull();
        await stream('Late withdrawn text');
        expect(props().streamStore.getSnapshot()).toBeNull();
        await mounted.unmount();
    });

    it('replaces an empty settled partial when a reset frame was missed', async () => {
        const mounted = await mount();
        await stream('Withdrawn text');
        harness.getTurn.mockResolvedValue({ turnId: 9, sessionId: 7, status: 'failed',
            terminalReason: 'malformed_output', partialContent: '', progress: [] });
        harness.getSession.mockResolvedValue(detail(null));
        await act(async () => {
            await vi.advanceTimersByTimeAsync(1_000);
            await vi.advanceTimersToNextTimerAsync();
        });

        expect(props().streamStore.getSnapshot()).toBeNull();
        expect(props().streaming).toBe(false);
        await stream('Late withdrawn text');
        expect(props().streamStore.getSnapshot()).toBeNull();
        await mounted.unmount();
    });

    it('clears a retained stream on reconnect when cleanup removed its settled turn', async () => {
        const mounted = await mount();
        await stream('Retained partial');
        harness.getTurn.mockResolvedValue({ turnId: 9, sessionId: 7, status: 'cancelled',
            terminalReason: null, partialContent: 'Retained partial', progress: [] });
        harness.getSession.mockResolvedValue(detail(null));
        await act(async () => { await vi.advanceTimersByTimeAsync(1_000); });
        expect(props().streamStore.getSnapshot()?.text).toBe('Retained partial');

        harness.getTurn.mockRejectedValue(new ApiError('Turn removed', 404));
        await act(async () => {
            socket().onStatusChange?.('connected');
            socket().onStatusChange?.('disconnected');
            socket().onStatusChange?.('connected');
        });
        expect(props().streamStore.getSnapshot()).toBeNull();
        expect(props().continuePrompt).toBeNull();
        await stream('Late withdrawn text');
        expect(props().streamStore.getSnapshot()).toBeNull();
        await mounted.unmount();
    });

    it('withdraws an ordinary retained tail when reconnect refresh observes physical purge', async () => {
        harness.getMissions.mockResolvedValue({ items: [mission()], nextCursor: null });
        const mounted = await mount();
        await stream('Ordinary retained partial');
        harness.getTurn.mockResolvedValue({ turnId: 9, sessionId: 7, status: 'cancelled',
            terminalReason: null, partialContent: 'Ordinary retained partial', progress: [] });
        harness.getSession.mockResolvedValue(detail(null));
        await act(async () => { await vi.advanceTimersByTimeAsync(1_000); });
        expect(props().streamStore.getSnapshot()?.text).toBe('Ordinary retained partial');

        harness.getMissions.mockResolvedValue({ items: [], nextCursor: null });
        const requests = harness.getMissions.mock.calls.length;
        await act(async () => { await vi.advanceTimersByTimeAsync(60_000); });
        expect(harness.getMissions).toHaveBeenCalledTimes(requests);
        await act(async () => {
            socket().onStatusChange?.('connected');
            socket().onStatusChange?.('disconnected');
            socket().onStatusChange?.('connected');
        });
        expect(props().streamStore.getSnapshot()).toBeNull();
        expect(props().continuePrompt).toBeNull();
        await stream('Late withdrawn text');
        expect(props().streamStore.getSnapshot()).toBeNull();
        await mounted.unmount();
    });
});
