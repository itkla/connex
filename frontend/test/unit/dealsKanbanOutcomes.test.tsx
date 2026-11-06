/** @vitest-environment jsdom */
import { fireEvent, render, screen, waitFor, cleanup } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import DealsKanban from '@/app/components/records/deals/DealsKanban';
import type { KanbanBoardProps } from '@/app/components/kanban/KanbanBoard';
import type { Deal, Pipeline, Stage } from '@/app/lib/types';
import messages from '@/messages/en/deals.json';

const mocks = vi.hoisted(() => ({
    board: vi.fn(), move: vi.fn(), reopen: vi.fn(), moved: vi.fn(), error: vi.fn(), success: vi.fn(), allowed: true,
}));
vi.mock('@/app/lib/api', () => ({
    getDealBoard: mocks.board, getCompaniesByIds: async () => [], getDealRisks: async () => [],
    moveDeal: mocks.move, reopenDeal: mocks.reopen,
}));
vi.mock('@/app/hooks/useApiErrorToast', () => ({ useApiErrorToast: () => mocks.error }));
vi.mock('@/app/hooks/usePermissions', () => ({ usePermission: () => mocks.allowed }));
vi.mock('@/app/lib/renderedWorkspace', () => ({ renderedWorkspaceId: () => 7, workspacePinnedRequest: () => ({ headers: { 'X-Workspace-Id': '7' } }) }));
vi.mock('@/app/lib/toast', () => ({ toastSuccess: mocks.success, toastError: mocks.error }));
vi.mock('@/app/components/records/deals/DealKanbanCard', () => ({ default: ({ deal }: { deal: Deal }) => <span>{deal.name}</span> }));
vi.mock('@/app/components/kanban/KanbanBoard', () => ({
    default: ({ items, onMove }: KanbanBoardProps<Deal>) => <div>{items.map((deal) => <div key={deal.id}>
        <span>{deal.name}</span>
        {[10, 20, 30].map((stage) => <button key={stage} disabled={!onMove} onClick={() => { void onMove?.(deal.id, String(stage), 0).catch(() => undefined); }}>Move to {stage}</button>)}
    </div>)}</div>,
}));

const pipeline: Pipeline = { id: 1, name: 'Sales', createdAt: '', updatedAt: '' };
const stages: Stage[] = [
    { id: 10, pipeline: 1, name: 'Proposal', position: 0, success: false, failure: false },
    { id: 20, pipeline: 1, name: 'Signed', position: 1, success: true, failure: false },
    { id: 30, pipeline: 1, name: 'Declined', position: 2, success: false, failure: true },
];
const initial: Deal = { id: 1, name: 'Example', pipeline: 1, stage: 10, position: 0, value: 100, actualValue: 0, currency: 'JPY', company: null, createdAt: '', updatedAt: '' };
function mount(deal = initial) {
    mocks.board.mockResolvedValue([deal]);
    return render(<NextIntlClientProvider locale="en" timeZone="UTC" messages={messages}>
        <DealsKanban timezone="UTC" deals={[deal]} pipelines={[pipeline]} stagesByPipeline={{ 1: stages }} companyById={new Map()}
            pipelineById={new Map([[1, pipeline]])} stageById={new Map(stages.map((stage) => [stage.id, stage]))} riskByDealId={new Map()}
            onMoved={mocks.moved} query="" filters={{}} segmentActive={false} segmentIds={null} segmentLoading={false} segmentError={null}
            currentUserId={1} revision={1} reduce />
    </NextIntlClientProvider>);
}

beforeEach(() => { vi.clearAllMocks(); mocks.allowed = true; });
afterEach(cleanup);

describe('deal board outcome safeguards', () => {
    for (const [stage, won, label] of [[20, true, 'Won'], [30, false, 'Lost']] as const) {
        it(`waits for confirmation before saving a ${label} stage move and acknowledges the returned outcome`, async () => {
            mocks.move.mockResolvedValue({ ...initial, stage, won });
            mount();
            fireEvent.click(await screen.findByText(`Move to ${stage}`));
            await screen.findByRole('dialog');
            expect(mocks.move).not.toHaveBeenCalled();
            expect(screen.getByText(/marks it/).textContent).toContain(label);
            expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Cancel' }));
            fireEvent.click(screen.getByRole('button', { name: 'Move and close' }));
            await waitFor(() => expect(mocks.move).toHaveBeenCalledWith(1, stage, 0, { headers: { 'X-Workspace-Id': '7' } }));
            await waitFor(() => expect(screen.getByRole('status').textContent).toContain(`Outcome: ${label}`));
            expect(mocks.reopen).not.toHaveBeenCalled();
            expect(mocks.moved).toHaveBeenCalledOnce();
        });
        it(`keeps a retained ${label} outcome when moving back and reopens only on explicit input`, async () => {
            const closed = { ...initial, stage, won };
            mocks.move.mockResolvedValue({ ...closed, stage: 10 });
            mocks.reopen.mockResolvedValue(initial);
            mount(closed);
            fireEvent.click(await screen.findByText('Move to 10'));
            await waitFor(() => expect(screen.getByRole('status').textContent).toContain(`still ${label}`));
            expect(screen.queryByRole('dialog')).toBeNull();
            expect(mocks.reopen).not.toHaveBeenCalled();
            fireEvent.click(screen.getByRole('button', { name: 'Reopen' }));
            await waitFor(() => expect(mocks.reopen).toHaveBeenCalledWith(1, { headers: { 'X-Workspace-Id': '7' } }));
            await waitFor(() => expect(screen.getByRole('status').textContent).toContain('Outcome: Open'));
        });
    }
    it('cancels a terminal move without sending any mutation', async () => {
        mount();
        fireEvent.click(await screen.findByText('Move to 20'));
        fireEvent.click(await screen.findByRole('button', { name: 'Cancel' }));
        await waitFor(() => expect(screen.getByRole('status').textContent).toContain('still in Proposal'));
        expect(mocks.move).not.toHaveBeenCalled();
        expect(mocks.reopen).not.toHaveBeenCalled();
    });
    it('surfaces a refused move without announcing a saved outcome', async () => {
        const error = new Error('refused');
        mocks.move.mockRejectedValue(error);
        mount();
        fireEvent.click(await screen.findByText('Move to 20'));
        fireEvent.click(await screen.findByRole('button', { name: 'Move and close' }));
        await waitFor(() => expect(mocks.error).toHaveBeenCalledWith(error, 'moveFailed'));
        expect(mocks.success).not.toHaveBeenCalled();
        expect(screen.getByRole('status').textContent).toBe('');
    });
    it('keeps Reopen available for retry after a refused reopen', async () => {
        mocks.move.mockResolvedValue({ ...initial, won: false });
        mocks.reopen.mockRejectedValue(new Error('refused'));
        mount({ ...initial, stage: 30, won: false });
        fireEvent.click(await screen.findByText('Move to 10'));
        fireEvent.click(await screen.findByRole('button', { name: 'Reopen' }));
        await waitFor(() => expect(mocks.error).toHaveBeenCalledWith(expect.any(Error), 'reopenFailed'));
        expect(screen.getByRole('status').textContent).toContain('still Lost');
        expect(screen.getByRole('button', { name: 'Reopen' }).hasAttribute('disabled')).toBe(false);
    });
    it('offers no move control without update permission', async () => {
        mocks.allowed = false;
        mount();
        expect((await screen.findByText('Move to 20')).hasAttribute('disabled')).toBe(true);
    });
});
