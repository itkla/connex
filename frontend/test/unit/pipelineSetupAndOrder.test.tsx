/** @vitest-environment jsdom */
import { act, useState, type ReactNode, useEffect, useRef } from 'react';
import { createPortal } from 'react-dom';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import NewPipelineDialog from '@/app/components/records/pipelines/NewPipelineDialog';
import EditPipelineSheet from '@/app/components/records/pipelines/EditPipelineSheet';
import { NewDealForm } from '@/app/components/records/deals/NewDealDialog';
import { insertOpenStage, moveStage } from '@/app/components/records/pipelines/stageDrafts';
import { createPipeline, replacePipelineStages } from '@/app/lib/api';
import { TooltipProvider } from '@/components/ui/tooltip';
import type { CreateDealPayload, CreatePipelinePayload, Pipeline, Stage } from '@/app/lib/types';

const state = vi.hoisted(() => ({ canManage: true, workspaceId: 9 }));
const reportError = vi.hoisted(() => vi.fn());
const createDeal = vi.hoisted(() => vi.fn());
const publishPipeline = vi.hoisted(() => vi.fn());
const noop = vi.hoisted(() => () => undefined);
vi.mock('next/navigation', () => ({ useRouter: () => ({ refresh: noop }) }));
vi.mock('next-intl', () => ({ useTranslations: () => (key: string, values?: Record<string, string | number>) => {
    if (key === 'stageName') return `Stage ${values?.position}`;
    if (key === 'starterPipeline') return 'Sales';
    if (key === 'starterOpen') return 'Lead';
    if (key === 'starterWon') return 'Won';
    if (key === 'starterLost') return 'Lost';
    return key;
} }));
vi.mock('@/app/hooks/useApiErrorToast', () => ({ useApiErrorToast: () => reportError }));
vi.mock('@/app/hooks/usePermissions', () => ({ usePermission: () => state.canManage }));
vi.mock('@/app/hooks/useCompanySearch', () => ({ useCompanySearch: () => ({ companies: [], options: [], error: null }) }));
vi.mock('@/app/hooks/useDuplicatePreflight', () => ({ useDuplicatePreflight: () => ({ status: 'idle', blocked: false }) }));
vi.mock('@/app/components/records/DuplicatePreflightWarning', () => ({ default: () => null }));
vi.mock('@/app/lib/toast', () => ({ toastSuccess: vi.fn(), toastError: vi.fn(), toastInfo: vi.fn() }));
vi.mock('@/app/lib/api', () => ({ createPipeline: vi.fn(), replacePipelineStages: vi.fn(), updatePipeline: vi.fn(), isFieldError: () => false }));
vi.mock('@/app/lib/renderedWorkspace', () => ({
    renderedWorkspaceId: () => state.workspaceId,
    workspacePinnedRequest: (id: number) => ({ headers: { 'X-Workspace-Id': String(id) } }),
}));
vi.mock('@/components/ui/dialog-status-cover', async (original) => ({
    ...await original<typeof import('@/components/ui/dialog-status-cover')>(), DialogStatusCover: () => null,
}));
vi.mock('@/components/ui/responsive-dialog', () => {
    const Pass = ({ children }: { children?: ReactNode }) => <>{children}</>;
    return {
        ResponsiveDialog: ({ open, onCloseComplete, children }: { open: boolean; onCloseComplete?: () => void; children?: ReactNode }) => {
            const wasOpen = useRef(open);
            useEffect(() => {
                if (wasOpen.current && !open) onCloseComplete?.();
                wasOpen.current = open;
            }, [open, onCloseComplete]);
            return open ? <>{children}</> : null;
        },
        ResponsiveDialogContent: ({ children }: { children?: ReactNode }) => createPortal(<div role="dialog">{children}</div>, document.body),
        ResponsiveDialogHeader: Pass, ResponsiveDialogTitle: Pass, ResponsiveDialogDescription: Pass,
        ResponsiveDialogFooter: Pass, ResponsiveDialogClose: Pass,
    };
});
vi.mock('@/app/components/records/quick-edit/QuickEditSheetShell', () => ({
    QuickEditSheetShell: ({ children, onSave }: { children?: ReactNode; onSave: () => void }) => <div>{children}<button onClick={onSave}>Save</button></div>,
    QuickEditRecordCard: ({ children }: { children?: ReactNode }) => <div>{children}</div>,
    QuickEditField: ({ children }: { children?: ReactNode }) => <div>{children}</div>,
}));

const pipeline: Pipeline = { id: 41, name: 'Sales', createdAt: '', updatedAt: '' };
const stages: Stage[] = [
    { id: 51, pipeline: 41, position: 0, name: 'Lead', success: false, failure: false },
    { id: 52, pipeline: 41, position: 1, name: 'Won', success: true, failure: false },
    { id: 53, pipeline: 41, position: 2, name: 'Lost', success: false, failure: true },
];
const deal: CreateDealPayload = { name: 'Pending renewal', value: 12345, currency: 'JPY', pipeline: 0, stage: 0, company: 17, expectedCloseDate: '2027-01-01' };
let root: Root;

function input(id: string): HTMLInputElement {
    const element = document.getElementById(id);
    if (!(element instanceof HTMLInputElement)) throw new Error(`Missing input ${id}`);
    return element;
}
async function change(id: string, value: string) {
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
    if (!setter) throw new Error('Missing input value setter');
    await act(async () => {
        setter.call(input(id), value);
        input(id).dispatchEvent(new Event('input', { bubbles: true }));
    });
}
async function click(label: string) {
    const element = [...document.querySelectorAll('button')].find((button) => button.getAttribute('aria-label') === label || button.textContent === label);
    if (!element) throw new Error(`Missing button ${label}`);
    await act(async () => element.click());
}
async function submitPipeline() {
    await act(async () => {
        input('pipeline-name').form?.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    });
}
async function render(child: ReactNode) {
    await act(async () => root.render(<TooltipProvider>{child}</TooltipProvider>));
}
function DealHarness() {
    const [payload, setPayload] = useState(deal);
    return <>
        <NewDealForm active payload={payload} setPayload={setPayload} pipelines={[]} stagesByPipeline={{}}
            isCreating={false} onCancel={noop} createNewDeal={createDeal} onPipelineCreated={publishPipeline} />
        <output>{JSON.stringify(payload)}</output>
    </>;
}

beforeEach(() => {
    vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true);
    vi.stubGlobal('matchMedia', (query: string) => ({ media: query, matches: false, addEventListener: noop, removeEventListener: noop }));
    vi.stubGlobal('ResizeObserver', class {
        observe() {}
        unobserve() {}
        disconnect() {}
    });
    vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => { callback(0); return 1; });
    state.canManage = true;
    state.workspaceId = 9;
    vi.mocked(createPipeline).mockResolvedValue(pipeline);
    vi.mocked(replacePipelineStages).mockResolvedValue(stages);
    const container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
});
afterEach(async () => {
    await act(async () => root.unmount());
    document.body.innerHTML = '';
    vi.resetAllMocks();
    vi.unstubAllGlobals();
});

describe('pipeline order and setup', () => {
    it('inserts open stages before terminal stages and preserves identities and flags while moving', () => {
        const added = { ...stages[0], id: 54, name: 'Discovery' };
        expect(insertOpenStage(stages, added).map((stage) => stage.id)).toEqual([51, 54, 52, 53]);
        expect(moveStage(stages, 2, 0)).toEqual([stages[2], stages[0], stages[1]]);
        expect(moveStage(stages, 0, -1)).toBe(stages);
    });

    it('focuses newly added stages in New pipeline, including insertion before terminal stages', async () => {
        function Harness() {
            const [payload, setPayload] = useState<CreatePipelinePayload>({ name: 'Sales', stages });
            return <NewPipelineDialog open onOpenChange={noop} payload={payload} setPayload={setPayload} isCreating={false} createNewPipeline={noop} />;
        }
        await render(<Harness />);
        await click('addStage');
        expect(document.activeElement).toBe(input('new-pipeline-stage-1'));
        await change('new-pipeline-stage-1', 'Discovery');
        expect(input('new-pipeline-stage-2').value).toBe('Won');
        expect(input('new-pipeline-stage-3').value).toBe('Lost');
    });

    it('moves and saves stages through the shared editor without changing terminal flags', async () => {
        await render(<EditPipelineSheet pipeline={pipeline} stages={stages} open onOpenChange={noop} />);
        await click('addStage');
        expect(document.activeElement).toBe(input('pipeline-stage-41-1'));
        await change('pipeline-stage-41-1', 'Discovery');
        const up = document.getElementById('pipeline-stage-41-1-up');
        await act(async () => up?.click());
        expect(document.activeElement?.id).toBe('pipeline-stage-41-0-up');
        expect(input('pipeline-stage-41-0').value).toBe('Discovery');
        expect(document.querySelector('[role="status"]')?.textContent).toBe('moved');
        await click('Save');
        expect(replacePipelineStages).toHaveBeenCalledWith(41, [51, 52, 53], [
            { name: 'Discovery', success: false, failure: false },
            { id: 51, name: 'Lead', success: false, failure: false },
            { id: 52, name: 'Won', success: true, failure: false },
            { id: 53, name: 'Lost', success: false, failure: true },
        ], expect.anything());
    });

    it('keeps every deal field through setup and selects the created pipeline and open stage', async () => {
        await render(<DealHarness />);
        await click('setupPipeline');
        await submitPipeline();
        expect(input('deal-name').value).toBe(deal.name);
        expect(input('deal-value').value).toBe('12345');
        expect(input('deal-currency').value).toBe('JPY');
        expect(input('deal-close').value).toBe('2027-01-01');
        expect(input('deal-pipeline').value).toBe('Sales');
        expect(input('deal-stage').value).toBe('Lead');
        expect(document.querySelector('output')?.textContent).toBe(JSON.stringify({ ...deal, pipeline: 41, stage: 51 }));
        expect(createDeal).not.toHaveBeenCalled();
        expect(publishPipeline).toHaveBeenCalledExactlyOnceWith(pipeline, stages);
    });

    it('retries a failed stage save on the created pipeline and pins the whole setup to its origin', async () => {
        vi.mocked(replacePipelineStages).mockRejectedValueOnce(new Error('Unavailable'));
        await render(<DealHarness />);
        await click('setupPipeline');
        state.workspaceId = 10;
        await submitPipeline();
        expect(reportError).toHaveBeenCalledWith(expect.any(Error), 'pipelineSetupFailed', 'pipelineSetupRetry');
        expect(input('deal-name').value).toBe(deal.name);
        await submitPipeline();
        expect(createPipeline).toHaveBeenCalledTimes(1);
        expect(replacePipelineStages).toHaveBeenCalledTimes(2);
        expect(vi.mocked(replacePipelineStages).mock.calls.every((call) => new Headers(call[3]?.headers).get('X-Workspace-Id') === '9')).toBe(true);
        expect(input('deal-stage').value).toBe('Lead');
    });

    it('refuses setup without an open stage and keeps the deal draft', async () => {
        await render(<DealHarness />);
        await click('setupPipeline');
        await click('removeStageAriaLabel');
        await submitPipeline();
        expect(createPipeline).not.toHaveBeenCalled();
        expect(document.querySelector('[role="alert"]')?.textContent).toBe('pipelineNeedsOpenStage');
        expect(input('deal-name').value).toBe(deal.name);
    });

    it('gives a member without pipeline management permission guidance without offering a refused write', async () => {
        state.canManage = false;
        await render(<DealHarness />);
        expect(document.body.textContent).toContain('pipelineSetupAskManager');
        expect(document.body.textContent).not.toContain('setupPipeline');
        expect(document.querySelector<HTMLButtonElement>('button[type="submit"]')?.disabled).toBe(true);
    });
});
