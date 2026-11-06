/** @vitest-environment jsdom */
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import type { PropsWithChildren } from 'react';
import Link from 'next/link';
import { NextIntlClientProvider } from 'next-intl';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import WorkflowEditor from '@/app/components/settings/workflows/WorkflowEditor';
import { createEmptyWorkflowGraph } from '@/app/components/settings/workflows/workflowGraph';
import { requestGuardedNavigation } from '@/app/hooks/useUnsavedChangesGuard';
import { openResult } from '@/app/lib/search/resultGroups';
import type { WorkflowDto } from '@/app/lib/types';
import common from '@/messages/en/common.json';
import workspace from '@/messages/en/workspace.json';

const mocks = vi.hoisted(() => ({
    push: vi.fn(), replace: vi.fn(), create: vi.fn(), getWorkflow: vi.fn(),
    back: vi.fn(), forward: vi.fn(), refresh: vi.fn(), prefetch: vi.fn(), bfcacheId: 'workflow-route',
}));
vi.mock('next/navigation', () => ({ useRouter: () => mocks, usePathname: () => window.location.pathname, useSearchParams: () => new URLSearchParams() }));
vi.mock('@xyflow/react', () => ({ ReactFlowProvider: ({ children }: PropsWithChildren) => children }));
vi.mock('@/app/hooks/useWorkspace', () => ({ useWorkspace: () => ({ activeWorkspaceId: 1, switching: false }) }));
vi.mock('@/app/hooks/usePermissions', () => ({ useGrantedPermissions: () => [] }));
vi.mock('@/app/components/settings/workflows/useWorkflowWorkspaceAccess', () => ({ useWorkflowWorkspaceAccess: () => ({ canRunAsSystem: true, members: [] }) }));
vi.mock('@/app/components/settings/workflows/WorkflowCanvasEditor', () => ({ default: () => null }));
vi.mock('@/app/components/settings/workflows/WorkflowOutlineEditor', () => ({ default: () => null }));
vi.mock('@/app/components/settings/workflows/WorkflowInspector', () => ({ default: () => null }));
vi.mock('@/app/components/settings/workflows/WorkflowConflictDialog', () => ({ default: () => null }));
vi.mock('@/app/components/settings/workflows/WorkflowRunsDialog', () => ({ default: () => null }));
vi.mock('@/app/components/settings/workflows/WorkflowSimulationDialog', () => ({ default: () => null }));
vi.mock('@/app/components/settings/workflows/WorkflowVersionsDialog', () => ({ default: () => null }));
vi.mock('@/app/lib/toast', () => ({ toastError: vi.fn(), toastSuccess: vi.fn() }));
vi.mock('@/app/lib/api', () => ({
    ApiError: class extends Error {}, createWorkflow: mocks.create,
    disableWorkflow: vi.fn(), enableWorkflow: vi.fn(), getWorkflowById: mocks.getWorkflow, getWorkflowVersions: async () => [],
    publishWorkflow: vi.fn(), saveWorkflowDraft: vi.fn(), search: vi.fn(), simulateWorkflow: vi.fn(), validateWorkflow: vi.fn(),
    getCampaignMessages: vi.fn(), getCampaigns: vi.fn(), getCompanies: async () => [], getPipelines: async () => [],
    getSegmentFields: async () => ({ industries: [], tags: [] }), getStagesByPipelineId: async () => [], getWorkflowRun: vi.fn(),
}));

const graph = createEmptyWorkflowGraph();
const created: WorkflowDto = {
    id: 41, name: 'Pending workflow', enabled: false, runtimeOwner: 'legacy', executionAvailable: true,
    draftRevision: 1, recordType: 'deal', executionMode: 'user', definition: graph.definition, canvas: graph.canvas,
    createdAt: '', updatedAt: '',
};

beforeEach(() => {
    vi.clearAllMocks();
    mocks.getWorkflow.mockResolvedValue(created);
    vi.stubGlobal('matchMedia', (media: string) => ({ media, matches: false, addEventListener() {}, removeEventListener() {} }));
    vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} });
});
afterEach(() => { cleanup(); vi.unstubAllGlobals(); window.history.replaceState({}, '', '/'); });

async function startCreation() {
    const pending = Promise.withResolvers<WorkflowDto>();
    mocks.create.mockReturnValue(pending.promise);
    render(<NextIntlClientProvider locale="en" messages={{ ...common, ...workspace }} timeZone="UTC">
        <Link href="/records/deals">Deals</Link>
        <WorkflowEditor triggeredSendEnabled={false} />
    </NextIntlClientProvider>);
    fireEvent.change(await screen.findByRole('textbox', { name: 'Workflow name' }), { target: { value: created.name } });
    fireEvent.click(screen.getByRole('button', { name: 'Save draft' }));
    expect(mocks.create).toHaveBeenCalledOnce();
    return pending;
}

describe('workflow creation departure', () => {
    it.each(['/workflows/41', '/workflows/41?mode=outline#selected-node'])(
        'keeps the existing workflow guarded when global search selects its current route (%s)', async (href) => {
            const nativeReplace = window.history.replaceState.bind(window.history);
            nativeReplace({ __NA: true }, '', href);
            try {
                render(<NextIntlClientProvider locale="en" messages={{ ...common, ...workspace }} timeZone="UTC">
                    <WorkflowEditor workflowId={created.id} triggeredSendEnabled={false} />
                </NextIntlClientProvider>);
                const input = await screen.findByRole('textbox', { name: 'Workflow name' });
                fireEvent.change(input, { target: { value: 'Keep this dirty workflow' } });
                act(() => openResult(mocks, href));
                expect(screen.queryByRole('dialog')).toBeNull();
                expect(mocks.push).not.toHaveBeenCalled();
                expect(input).toHaveProperty('value', 'Keep this dirty workflow');
                const unload = new Event('beforeunload', { cancelable: true });
                window.dispatchEvent(unload);
                expect(unload.defaultPrevented).toBe(true);

                act(() => openResult(mocks, '/workflows/41?mode=different'));
                fireEvent.click(await screen.findByRole('button', { name: 'Discard changes' }));
                expect(mocks.push).toHaveBeenCalledExactlyOnceWith('/workflows/41?mode=different');
                const afterQuery = new Event('beforeunload', { cancelable: true });
                window.dispatchEvent(afterQuery);
                expect(afterQuery.defaultPrevented).toBe(true);
                act(() => openResult(mocks, '/records/deals'));
                await screen.findByRole('dialog', { name: 'Discard unsaved changes?' });
                fireEvent.click(screen.getByRole('button', { name: 'Keep editing' }));
                const laterUnload = new Event('beforeunload', { cancelable: true });
                window.dispatchEvent(laterUnload);
                expect(laterUnload.defaultPrevented).toBe(true);
                expect(input).toHaveProperty('value', 'Keep this dirty workflow');
            } finally {
                nativeReplace({}, '', '/');
            }
        },
    );
    it.each(['back', 'sidebar', 'shell', 'browser'])('invalidates creation synchronously on confirmed %s departure while the editor remains mounted', async (exit) => {
        window.history.replaceState({ __NA: true }, '', '/workflows/new');
        const pending = await startCreation();
        if (exit === 'back') fireEvent.click(screen.getByRole('button', { name: 'Back to workflows' }));
        else if (exit === 'sidebar') fireEvent.click(screen.getByRole('link', { name: 'Deals' }));
        else if (exit === 'shell') act(() => requestGuardedNavigation(() => mocks.push('/records/deals')));
        else {
            window.history.replaceState(null, '', '/records/deals');
            act(() => { window.dispatchEvent(new PopStateEvent('popstate', { state: null })); });
        }
        fireEvent.click(await screen.findByRole('button', { name: 'Discard changes' }));
        if (exit === 'browser') expect(mocks.replace).toHaveBeenCalledWith('/records/deals');
        else expect(mocks.push).toHaveBeenCalledWith(exit === 'back' ? '/workflows' : '/records/deals');
        const replacements = mocks.replace.mock.calls.length;
        await act(async () => { pending.resolve(created); });
        expect(mocks.replace).toHaveBeenCalledTimes(replacements);
    });

    it('keeps the creation continuation when departure is canceled', async () => {
        const pending = await startCreation();
        fireEvent.click(screen.getByRole('link', { name: 'Deals' }));
        fireEvent.click(await screen.findByRole('button', { name: 'Keep editing' }));
        await act(async () => { pending.resolve(created); });
        expect(mocks.replace).toHaveBeenCalledExactlyOnceWith('/workflows/41');
    });

    it('invalidates creation after confirming an unattributed history traversal', async () => {
        vi.stubGlobal('navigation', undefined);
        const nativeReplace = window.history.replaceState.bind(window.history);
        nativeReplace({ __NA: true }, '', '/workflows/new');
        try {
            const pending = await startCreation();
            nativeReplace({}, '', '/docs/earlier');
            act(() => { window.dispatchEvent(new PopStateEvent('popstate', { state: {} })); });
            expect(window.location.pathname).toBe('/workflows/new');
            fireEvent.click(await screen.findByRole('button', { name: 'Discard changes' }));
            expect(mocks.replace).toHaveBeenCalledExactlyOnceWith('/docs/earlier');
            await act(async () => { pending.resolve(created); });
            expect(mocks.replace).toHaveBeenCalledExactlyOnceWith('/docs/earlier');
        } finally {
            nativeReplace({}, '', '/');
        }
    });
});
