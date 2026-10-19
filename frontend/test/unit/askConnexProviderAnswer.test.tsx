/** @vitest-environment jsdom */

import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import AskConnexProvider from '@/app/components/ask-connex/AskConnexProvider';
import AskConnexWorkspaceMount from '@/app/components/ask-connex/AskConnexWorkspaceMount';
import { getAiChatSession, getAiChatTurn, startAiChatTurn } from '@/app/lib/api';
import type { AiChatSocketHandlers } from '@/app/lib/realtime';
import type { AiChatSessionDetail, AiChatTurn } from '@/app/lib/types';
import { TooltipProvider } from '@/components/ui/tooltip';
import enActivity from '@/messages/en/activity.json';
import enAssistant from '@/messages/en/assistant.json';
import enCommon from '@/messages/en/common.json';
import enContacts from '@/messages/en/contacts.json';
import enReports from '@/messages/en/reports.json';

const boundary = vi.hoisted(() => {
    const socket: { current: AiChatSocketHandlers | null } = { current: null };
    return {
        socket,
        canPersist: () => true,
        showApiError: vi.fn(),
        router: { push: vi.fn(), replace: vi.fn(), refresh: vi.fn() },
        context: { user: { id: 3, displayName: 'Aiko' }, record: null, selection: null },
    };
});

vi.mock('next/navigation', () => ({
    useParams: () => ({ sessionId: '4' }),
    usePathname: () => '/ask-connex/4',
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
vi.mock('@/app/hooks/useNow', () => ({
    useLiveNow: () => Date.parse('2026-10-18T12:00:00Z'),
    useNow: () => Date.parse('2026-10-18T12:00:00Z'),
}));
vi.mock('@/app/hooks/useApiErrorToast', () => ({ useApiErrorToast: () => boundary.showApiError }));
vi.mock('@/app/hooks/useAccountStorageGeneration', () => ({ useAccountStorageGeneration: () => boundary.canPersist }));
vi.mock('@/app/lib/realtime', () => ({
    createAiChatSocket: (handlers: AiChatSocketHandlers) => {
        boundary.socket.current = handlers;
        return { activate: () => {}, deactivate: () => {} };
    },
}));
vi.mock('@/app/lib/api', async (importOriginal) => ({
    ...await importOriginal<typeof import('@/app/lib/api')>(),
    getAiChatSessions: vi.fn().mockResolvedValue({ items: [], total: 0 }),
    getAiChatInvitations: vi.fn().mockResolvedValue({ items: [], total: 0 }),
    getAiChatAttachments: vi.fn().mockResolvedValue([]),
    getAiAssistantToolCalls: vi.fn().mockResolvedValue([]),
    getAiChatSession: vi.fn(),
    getAiChatTurn: vi.fn(),
    getAiGenerationStatus: vi.fn().mockResolvedValue({
        handle: 'generation-9', kind: 'assistant_turn', status: 'resolved',
        result: { turnId: 9, status: 'resolved' }, reason: null,
        retryAfterMs: 0, pollWindowMs: 30_000, expiresAt: '2026-10-18T12:30:00Z',
    }),
    resolveAcceptedAiGeneration: vi.fn().mockResolvedValue({ turnId: 9, status: 'resolved' }),
    startAiChatTurn: vi.fn(),
}));

const QUESTION = 'connex_script_browser_send_tools_answer check Quillfeather before I call them';
const ANSWER = 'I read [the contact](person:42) and nothing is waiting on you there.';
const DETAIL: AiChatSessionDetail = {
    session: {
        id: 4,
        workspaceId: 7,
        createdByUserId: 3,
        title: 'Scripted trajectory',
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
    messages: { items: [], total: 0 },
};
const TURN: AiChatTurn = {
    sessionId: 4, turnId: 9, status: 'resolved', terminalReason: null,
    partialContent: null, requestedByCurrentUser: true,
    progress: [
        { seq: 0, source: 'scope', status: 'complete', count: null, truncated: false },
        { seq: 1, source: 'records', status: 'complete', count: 1, truncated: false },
        { seq: 65, source: 'answer', status: 'complete', count: null, truncated: false },
    ],
};

beforeEach(() => {
    boundary.socket.current = null;
    localStorage.clear();
    vi.stubGlobal('ResizeObserver', class {
        observe() {}
        unobserve() {}
        disconnect() {}
    });
    Object.defineProperty(HTMLElement.prototype, 'scrollTo', { configurable: true, value: vi.fn() });
    Object.defineProperty(Element.prototype, 'getAnimations', { configurable: true, value: () => [] });
    vi.mocked(getAiChatSession).mockResolvedValue(DETAIL);
    vi.mocked(startAiChatTurn).mockResolvedValue({
        sessionId: 4, turnId: 9, generationHandle: 'generation-9', status: 'accepted',
    });
});

afterEach(() => {
    cleanup();
    Reflect.deleteProperty(HTMLElement.prototype, 'scrollTo');
    Reflect.deleteProperty(Element.prototype, 'getAnimations');
    vi.clearAllMocks();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
});

describe('the provider adopts a successful answer into the real drawer', () => {
    it.each([
        { projection: 'omitted', activeTurn: {} },
        { projection: 'explicit null', activeTurn: { activeTurn: null } },
    ])('renders the settled citation when activeTurn is $projection', async ({ activeTurn }) => {
        const terminal = Promise.withResolvers<AiChatTurn>();
        const transcript = Promise.withResolvers<AiChatSessionDetail>();
        vi.mocked(getAiChatTurn).mockReturnValue(terminal.promise);
        render(
            <NextIntlClientProvider locale="en" timeZone="UTC" messages={{
                ...enCommon, ...enActivity, ...enAssistant, ...enContacts, ...enReports,
            }}>
                <TooltipProvider>
                    <AskConnexProvider><AskConnexWorkspaceMount /></AskConnexProvider>
                </TooltipProvider>
            </NextIntlClientProvider>,
        );

        const composer = await screen.findByRole('combobox', { name: enCommon.AskConnex.composerAria });
        await waitFor(() => expect(composer.getAttribute('contenteditable')).toBe('true'));
        vi.mocked(getAiChatSession).mockReturnValue(transcript.promise);
        composer.textContent = QUESTION;
        fireEvent.input(composer);
        fireEvent.click(screen.getByRole('button', { name: enCommon.AskConnex.send }));
        await waitFor(() => expect(startAiChatTurn).toHaveBeenCalledExactlyOnceWith(
            4, expect.objectContaining({ content: QUESTION }),
            { headers: expect.any(Headers) },
        ));
        expect(new Headers(vi.mocked(startAiChatTurn).mock.calls[0]?.[2]?.headers).get('X-Workspace-Id')).toBe('7');
        await waitFor(() => expect(getAiChatTurn).toHaveBeenCalled());
        const socket = boundary.socket.current;
        if (socket === null) throw new Error('The provider did not connect its socket');
        await act(async () => {
            socket.onDelta?.({
                workspaceId: 7, sessionId: 4, turnId: 9, seq: 0, kind: 'delta', text: ANSWER,
            });
        });
        expect((await screen.findByText(/nothing is waiting on you there/)).textContent).toContain('nothing is waiting on you there.');

        await act(async () => { terminal.resolve(TURN); });
        await waitFor(() => expect(getAiChatSession).toHaveBeenCalledTimes(2));
        await act(async () => {
            transcript.resolve({
                ...DETAIL,
                ...activeTurn,
                messages: {
                    total: 2,
                    items: [
                        {
                            id: 22, sessionId: 4, seq: 1, authorKind: 'user', authorUserId: 3,
                            authorDisplayName: 'Aiko', content: QUESTION, createdAt: '2026-10-18 12:00:00',
                        },
                        {
                            id: 23, sessionId: 4, seq: 2, authorKind: 'assistant', authorUserId: null,
                            authorDisplayName: null, content: ANSWER, createdAt: '2026-10-18 12:00:01',
                            citations: [{ handle: 'r1', kind: 'person', id: 42 }], suggestions: [],
                        },
                    ],
                },
            });
        });

        const chip = await screen.findByRole('link', { name: 'the contact' });
        expect(chip.getAttribute('href')).toBe('/records/contacts/42');
        expect(screen.getAllByText(/nothing is waiting on you there/)).toHaveLength(1);
        expect(screen.getByRole('combobox', { name: enCommon.AskConnex.composerAria })).toBeTruthy();
        expect(boundary.showApiError).not.toHaveBeenCalled();
    });
});
