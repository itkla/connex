import { act } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type AskConnexDrawer from '@/app/components/ask-connex/AskConnexDrawer';
import AskConnexProvider from '@/app/components/ask-connex/AskConnexProvider';
import {
    getAiAssistantToolCalls,
    getAiChatSession,
    getAiChatTurn,
    startAiChatTurn,
} from '@/app/lib/api';
import { askConnexSessionStorageKey } from '@/app/lib/askConnex';
import type { AiAssistantToolCall, AiChatSessionDetail, AiChatTurn } from '@/app/lib/types';
import { installInteractiveDocument } from '@/test/unit/helpers/interactiveDocument';
import { askConnexToolCall } from '@/test/unit/helpers/askConnexToolCards';

type DrawerProps = Parameters<typeof AskConnexDrawer>[0];

const boundary = vi.hoisted(() => {
    const drawer: { current: DrawerProps | null } = { current: null };
    return {
        drawer,
        canPersist: () => true,
        translate: (key: string) => key,
        showApiError: vi.fn(),
        router: { push: vi.fn(), replace: vi.fn(), refresh: vi.fn() },
        context: { user: { id: 3, displayName: 'Aiko' }, record: null, selection: null },
    };
});

vi.mock('@/app/components/ask-connex/AskConnexDrawer', () => ({
    default: (props: DrawerProps) => {
        boundary.drawer.current = props;
        return null;
    },
}));
vi.mock('next-intl', () => ({ useLocale: () => 'en', useTranslations: () => boundary.translate }));
vi.mock('next/navigation', () => ({
    useParams: () => ({}),
    usePathname: () => '/records/contacts',
    useRouter: () => boundary.router,
}));
vi.mock('@/app/hooks/useActions', () => ({
    useActions: () => ({ context: boundary.context }),
    useRegisterActions: () => {},
}));
vi.mock('@/app/hooks/useWorkspace', () => ({ useWorkspace: () => ({ activeWorkspaceId: 7, switching: false }) }));
vi.mock('@/app/hooks/usePermissions', () => ({
    usePermissionCheck: (permission: string) => permission === 'AI_USE' ? 'granted' : 'denied',
}));
vi.mock('@/app/hooks/useAskConnexSkills', () => ({ useAskConnexSkills: () => [] }));
vi.mock('@/app/hooks/useIsMobile', () => ({ useIsMobile: () => false }));
vi.mock('@/app/hooks/useNow', () => ({ useLiveNow: () => Date.parse('2026-10-18T12:00:00Z') }));
vi.mock('@/app/hooks/useApiErrorToast', () => ({ useApiErrorToast: () => boundary.showApiError }));
vi.mock('@/app/hooks/useAccountStorageGeneration', () => ({ useAccountStorageGeneration: () => boundary.canPersist }));
vi.mock('@/app/lib/realtime', () => ({
    createAiChatSocket: () => ({ activate: () => {}, deactivate: () => {} }),
}));
vi.mock('@/app/lib/api', async (importOriginal) => ({
    ...await importOriginal<typeof import('@/app/lib/api')>(),
    getAiChatSessions: vi.fn().mockResolvedValue({ items: [], total: 0 }),
    getAiChatInvitations: vi.fn().mockResolvedValue({ items: [], total: 0 }),
    getAiChatAttachments: vi.fn().mockResolvedValue([]),
    getAiChatSession: vi.fn(),
    getAiChatTurn: vi.fn(),
    getAiAssistantToolCalls: vi.fn(),
    startAiChatTurn: vi.fn(),
}));

const QUESTION = 'Log a call with Sato and create a follow-up task.';
const TURN: AiChatTurn = {
    sessionId: 4,
    turnId: 9,
    status: 'timed_out',
    terminalReason: 'turn_deadline_exceeded',
    partialContent: 'The follow-up needs a due date.',
    requestedByCurrentUser: true,
    progress: [
        { seq: 0, source: 'scope', status: 'complete', count: null, truncated: false },
        { seq: 1, source: 'records', status: 'complete', count: 1, truncated: false },
        { seq: 65, source: 'answer', status: 'timed_out', count: null, truncated: false },
    ],
};
const DETAIL: AiChatSessionDetail = {
    session: {
        id: 4,
        workspaceId: 7,
        createdByUserId: 3,
        title: 'Sato follow-up',
        visibility: 'private',
        status: 'active',
        archived: false,
        ownedByCurrentUser: true,
        historySummarized: false,
        participationStatus: null,
        lastMessageAt: '2026-10-18 11:59:00',
        archivedAt: null,
        createdAt: '2026-10-18 11:59:00',
        updatedAt: '2026-10-18 11:59:00',
    },
    messages: {
        items: [{
            id: 22,
            sessionId: 4,
            seq: 1,
            authorKind: 'user',
            authorUserId: 3,
            authorDisplayName: 'Aiko',
            content: QUESTION,
            createdAt: '2026-10-18 11:59:00',
        }],
        total: 1,
    },
    activeTurn: TURN,
};

function drawer(): DrawerProps {
    if (boundary.drawer.current === null) throw new Error('The provider did not render the drawer');
    return boundary.drawer.current;
}

async function mountProvider() {
    const interactive = installInteractiveDocument();
    const storage = new Map([[askConnexSessionStorageKey(3, 7), '4']]);
    Object.defineProperty(window, 'localStorage', {
        value: {
            getItem: (key: string) => storage.get(key) ?? null,
            setItem: (key: string, value: string) => storage.set(key, value),
            removeItem: (key: string) => storage.delete(key),
        },
        configurable: true,
    });
    const { createRoot } = await import('react-dom/client');
    const root = createRoot(interactive.container);
    await act(async () => { root.render(<AskConnexProvider>{null}</AskConnexProvider>); });
    return root;
}

describe('the provider guards the Retry callback', () => {
    beforeEach(() => {
        boundary.drawer.current = null;
        vi.mocked(getAiChatSession).mockResolvedValue(DETAIL);
        vi.mocked(getAiChatTurn).mockResolvedValue(TURN);
        vi.mocked(getAiAssistantToolCalls).mockResolvedValue([]);
        vi.mocked(startAiChatTurn).mockRejectedValue(new Error('Submission stopped by the test'));
    });

    afterEach(() => {
        vi.clearAllMocks();
        vi.unstubAllGlobals();
    });

    it.each(['executed', 'proposed'] as const)('refuses direct retry with an %s automatic write', async (status) => {
        const card = askConnexToolCall({ toolName: 'create_task', tier: 'auto', status });
        vi.mocked(getAiAssistantToolCalls).mockResolvedValue([card]);
        const root = await mountProvider();
        try {
            expect(drawer().turn.phase).toBe('timed_out');
            expect(drawer().turnWriteState).toBe(status === 'executed' ? 'committed' : 'uncertain');
            expect(drawer().continuePrompt).toContain('continuePrompt');
            expect(drawer().toolCalls).toEqual([expect.objectContaining({ id: card.id, status })]);
            await act(async () => { drawer().onRetryTurn(); });
            expect(startAiChatTurn).not.toHaveBeenCalled();
        } finally {
            await act(async () => { root.unmount(); });
        }
    });

    it('withholds retry until the settled tool-card read finishes', async () => {
        const pending = Promise.withResolvers<AiAssistantToolCall[]>();
        vi.mocked(getAiAssistantToolCalls)
            .mockResolvedValueOnce([])
            .mockResolvedValueOnce([])
            .mockReturnValueOnce(pending.promise);
        const root = await mountProvider();
        try {
            expect(drawer().turn.phase).toBe('timed_out');
            expect(drawer().turnWriteState).toBe('uncertain');
            await act(async () => { drawer().onRetryTurn(); });
            expect(startAiChatTurn).not.toHaveBeenCalled();
            await act(async () => { pending.resolve([]); });
            expect(drawer().turnWriteState).toBe('none');
        } finally {
            await act(async () => { root.unmount(); });
        }
    });

    it('refuses retry when progress may include writes missing from the action list', async () => {
        const turn: AiChatTurn = {
            ...TURN,
            progress: [...TURN.progress, {
                seq: 2, source: 'tasks', status: 'complete', count: 1, truncated: false,
            }],
        };
        vi.mocked(getAiChatSession).mockResolvedValue({ ...DETAIL, activeTurn: turn });
        vi.mocked(getAiChatTurn).mockResolvedValue(turn);
        const root = await mountProvider();
        try {
            expect(drawer().toolCalls).toEqual([]);
            expect(drawer().turnWriteState).toBe('uncertain');
            await act(async () => { drawer().onRetryTurn(); });
            expect(startAiChatTurn).not.toHaveBeenCalled();
        } finally {
            await act(async () => { root.unmount(); });
        }
    });

    it('replays the original question when the failed turn is known to have no writes', async () => {
        vi.mocked(getAiAssistantToolCalls).mockResolvedValue([
            askConnexToolCall({ turnId: 8, status: 'executed' }),
        ]);
        const root = await mountProvider();
        try {
            expect(drawer().turnWriteState).toBe('none');
            await act(async () => { drawer().onRetryTurn(); });
            expect(startAiChatTurn).toHaveBeenCalledExactlyOnceWith(4, expect.objectContaining({
                content: QUESTION,
            }));
        } finally {
            await act(async () => { root.unmount(); });
        }
    });
});
