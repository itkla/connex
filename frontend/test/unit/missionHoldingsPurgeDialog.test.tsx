import { act, type ButtonHTMLAttributes, type ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import MissionHoldingsPurgeDialog from '@/app/components/organization/MissionHoldingsPurgeDialog';
import type { AiAgentHoldingsPurge, DataSubjectRequest } from '@/app/lib/types';
import en from '@/messages/en/organization.json';
import ja from '@/messages/ja/organization.json';
import { installInteractiveDocument } from '@/test/unit/helpers/interactiveDocument';

const harness = vi.hoisted(() => ({
    locale: 'en',
    checks: new Map<string, (value: boolean) => void>(),
    stored: new Map<string, string>(),
    initiate: vi.fn<(...args: unknown[]) => Promise<AiAgentHoldingsPurge>>(),
    get: vi.fn<(...args: unknown[]) => Promise<AiAgentHoldingsPurge>>(),
    error: vi.fn(),
}));

function translated(namespace: string, key: string): string {
    let value: unknown = harness.locale === 'ja' ? ja : en;
    for (const part of `${namespace}.${key}`.split('.')) {
        if (typeof value !== 'object' || value === null) throw new Error('Unknown translation');
        value = Reflect.get(value, part);
    }
    if (typeof value !== 'string') throw new Error('Translation is not text');
    return value;
}

vi.mock('next-intl', () => ({ useTranslations: (namespace: string) => (key: string) => translated(namespace, key) }));
vi.mock('@/app/hooks/useAccountStorageGeneration', () => ({ useAccountStorageGeneration: () => () => true }));
vi.mock('@/app/hooks/useApiErrorToast', () => ({ useApiErrorToast: () => harness.error }));
vi.mock('@/app/lib/browserAccountStorage', () => ({ currentAccountBinding: () => ({ identity: 'account' }) }));
vi.mock('@/app/lib/api', async (original) => ({
    ...await original<typeof import('@/app/lib/api')>(),
    purgeAiAgentHoldings: harness.initiate,
    getAiAgentHoldingsPurge: harness.get,
}));
vi.mock('@/components/ui/button', () => ({ Button: ({ children, onClick, disabled, type }: ButtonHTMLAttributes<HTMLButtonElement>) =>
    <button type={type} disabled={disabled} onClick={onClick}>{children}</button> }));
vi.mock('@/components/ui/checkbox', () => ({ Checkbox: ({ id, onCheckedChange }: { id: string; onCheckedChange: (value: boolean) => void }) => {
    harness.checks.set(id, onCheckedChange);
    return <span />;
} }));
vi.mock('@/components/ui/label', () => ({ Label: ({ children }: { children: ReactNode }) => <span>{children}</span> }));
vi.mock('@/components/ui/dialog', () => {
    const Content = ({ children }: { children: ReactNode }) => <div>{children}</div>;
    return { Dialog: Content, DialogContent: Content, DialogDescription: Content,
        DialogFooter: Content, DialogHeader: Content, DialogTitle: Content };
});

const request: DataSubjectRequest = {
    id: 41, orgId: 3, snapshotToken: 'dsr1.snapshot', requestType: 'cease_use', status: 'received',
    requesterName: 'Requester', subjectName: 'Subject', receivedAt: '2026-10-19T00:00:00Z',
    createdAt: '2026-10-19T00:00:00Z', updatedAt: '2026-10-19T00:00:00Z',
};

function operation(version: 1 | 2): AiAgentHoldingsPurge {
    return { schemaVersion: version, operationId: 'a89eb971-d8e0-4dab-8344-bb2ab2bdd261', requestId: '41',
        scope: version === 1 ? 'organization_mission_holdings_v1' : 'organization_assistant_holdings_v2',
        cutoffPolicy: version === 1 ? 'contents_at_each_workspace_first_invalidation_v1' : 'organization_generation_at_acceptance_v2',
        acceptedAt: '2026-10-19T00:00:00Z', state: 'purging', workspaceCount: 1,
        completedWorkspaceCount: 0, completedAt: null, reasonCode: null };
}

async function mount() {
    const interactive = installInteractiveDocument();
    const storage = { getItem: (key: string) => harness.stored.get(key) ?? null,
        setItem: (key: string, value: string) => harness.stored.set(key, value) };
    vi.stubGlobal('localStorage', storage);
    Object.assign(window, { localStorage: storage, setInterval: globalThis.setInterval, clearInterval: globalThis.clearInterval });
    const { createRoot } = await import('react-dom/client');
    const root = createRoot(interactive.container);
    await act(async () => root.render(<MissionHoldingsPurgeDialog orgId={3} request={request} onClose={() => {}} onStale={() => {}} />));
    return { ...interactive, unmount: () => act(async () => root.unmount()) };
}

describe('versioned assistant erasure acknowledgment', () => {
    beforeEach(() => {
        vi.useFakeTimers();
        vi.clearAllMocks();
        harness.checks.clear();
        harness.stored.clear();
        harness.initiate.mockResolvedValue(operation(2));
    });
    afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); });

    it.each(['en', 'ja'])('new submissions disclose and acknowledge all-chat scope in %s', async (locale) => {
        harness.locale = locale;
        const mounted = await mount();
        try {
            expect(mounted.container.textContent).toContain(translated('OrgDataRequests.assistantPurge', 'scope'));
            await act(async () => { for (const acknowledge of harness.checks.values()) acknowledge(true); });
            const confirm = mounted.elements.find((element) => element.tagName === 'BUTTON'
                && element.textContent === translated('OrgDataRequests.assistantPurge', 'confirm'));
            if (!confirm) throw new Error('Erasure confirmation is missing');
            await act(async () => { mounted.dispatch('click', confirm); });
            expect(harness.initiate).toHaveBeenCalledWith(3, 41, expect.objectContaining({
                scopeAcknowledgement: 'organization_assistant_holdings_v2',
                cutoffPolicyAcknowledgement: 'organization_generation_at_acceptance_v2',
            }), expect.anything());
        } finally { await mounted.unmount(); }
    });

    it.each(['en', 'ja'])('existing v1 receipts retain their original copy and identity in %s', async (locale) => {
        harness.locale = locale;
        const accepted = operation(1);
        harness.get.mockResolvedValue(accepted);
        harness.stored.set('connex:mission-purge:account:3:41', JSON.stringify({
            idempotencyKey: '04125228-0938-44aa-ae96-113e20e5a5c3', operationId: accepted.operationId,
        }));
        const mounted = await mount();
        try {
            expect(mounted.container.textContent).toContain(translated('OrgDataRequests.missionPurge', 'title'));
            expect(harness.get).toHaveBeenCalledWith(3, 41, accepted.operationId, expect.anything());
            expect(harness.initiate).not.toHaveBeenCalled();
        } finally { await mounted.unmount(); }
    });
});
