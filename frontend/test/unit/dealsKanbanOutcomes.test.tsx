/** @vitest-environment jsdom */
import { fireEvent, render, screen, waitFor, cleanup, within } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import jaMessages from '@/messages/ja/deals.json';
import enCommon from '@/messages/en/common.json';
import jaCommon from '@/messages/ja/common.json';
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
    default: ({ items, onBeforeMove, onMove }: KanbanBoardProps<Deal>) => <div>{items.map((deal) => <div key={deal.id} data-testid={`deal-${deal.id}`} data-position={deal.position}>
        <button onClick={() => { void (async () => { if (!onBeforeMove || await onBeforeMove(deal.id, '20', 2)) await onMove?.(deal.id, '20', 2); })().catch(() => undefined); }}>Move to end</button>
        <span>{deal.name}</span>
        {[10, 20, 30].map((stage) => <button key={stage} disabled={!onMove} onClick={() => { void (async () => { if (!onBeforeMove || await onBeforeMove(deal.id, String(stage), 0)) await onMove?.(deal.id, String(stage), 0); })().catch(() => undefined); }}>Move to {stage}</button>)}
    </div>)}</div>,
}));

const pipeline: Pipeline = { id: 1, name: 'Sales', createdAt: '', updatedAt: '' };
const stages: Stage[] = [
    { id: 10, pipeline: 1, name: 'Proposal', position: 0, success: false, failure: false },
    { id: 20, pipeline: 1, name: 'Signed', position: 1, success: true, failure: false },
    { id: 30, pipeline: 1, name: 'Declined', position: 2, success: false, failure: true },
];
const initial: Deal = { id: 1, name: 'Example', pipeline: 1, stage: 10, position: 0, value: 100, actualValue: 0, currency: 'JPY', company: null, createdAt: '', updatedAt: '' };
function content(deal = initial, locale: 'en' | 'ja' = 'en', revision = 1) {
    return <NextIntlClientProvider locale={locale} timeZone="UTC" messages={locale === 'en' ? { ...enCommon, ...messages } : { ...jaCommon, ...jaMessages }}>
        <DealsKanban timezone="UTC" deals={[deal]} pipelines={[pipeline]} stagesByPipeline={{ 1: stages }} companyById={new Map()}
            pipelineById={new Map([[1, pipeline]])} stageById={new Map(stages.map((stage) => [stage.id, stage]))} riskByDealId={new Map()}
            onMoved={mocks.moved} query="" filters={{}} segmentActive={false} segmentIds={null} segmentLoading={false} segmentError={null}
            currentUserId={1} revision={revision} reduce />
    </NextIntlClientProvider>;
}
function mount(deal = initial, locale: 'en' | 'ja' = 'en', extraDeals: Deal[] = []) {
    mocks.board.mockResolvedValue([deal, ...extraDeals]);
    return render(content(deal, locale));
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

for (const locale of ['en', 'ja'] as const) {
    const t = locale === 'en' ? messages.DealsKanban : jaMessages.DealsKanban;
    it(`${locale}: discloses a fresh win's unknown final amount, currency and frozen value`, async () => {
        mount({ ...initial, currency: 'USD', value: 1234.56, actualValue: 987.65, valueSource: 'line_items' }, locale);
        fireEvent.click(await screen.findByText('Move to 20'));
        const dialog = await screen.findByRole('dialog');
        expect(dialog.textContent).toContain(t.confirmValueFreshWin.replace('{current}', '1,234.56 USD').replace('{currency}', 'USD'));
        expect(mocks.move).not.toHaveBeenCalled();
    });
    it(`${locale}: names the realized revenue reset when a won deal moves to Lost`, async () => {
        mount({ ...initial, won: true, stage: 20, currency: 'USD', actualValue: 750.25 }, locale);
        fireEvent.click(await screen.findByText('Move to 30'));
        const dialog = await screen.findByRole('dialog');
        expect(dialog.textContent).toContain(t.confirmValueLost.replace('{current}', '750.25 USD').replace('{amount}', '0.00 USD'));
        expect(mocks.move).not.toHaveBeenCalled();
    });
    it(`${locale}: keeps an existing win amount frozen when moving to another won stage`, async () => {
        mount({ ...initial, won: true, actualValue: 42.75, currency: 'JPY' }, locale);
        fireEvent.click(await screen.findByText('Move to 20'));
        const dialog = await screen.findByRole('dialog');
        expect(dialog.textContent).toContain(t.confirmValueFrozen.replace('{amount}', '42.75 JPY'));
        expect(mocks.move).not.toHaveBeenCalled();
    });
}

it('adopts the returned clamped position and announces it even when the board refresh fails', async () => {
    const saved = { ...initial, stage: 20, won: true, position: 0 };
    mocks.move.mockResolvedValue(saved);
    const view = mount(initial, 'en', [{ ...initial, id: 2, name: 'Other A', stage: 20 }, { ...initial, id: 3, name: 'Other B', stage: 20, position: 1 }]);
    fireEvent.click(within(await screen.findByTestId('deal-1')).getByText('Move to end'));
    fireEvent.click(await screen.findByRole('button', { name: 'Move and close' }));
    await waitFor(() => expect(mocks.move).toHaveBeenCalledWith(1, 20, 2, expect.any(Object)));
    await waitFor(() => expect(screen.getByTestId('deal-1').getAttribute('data-position')).toBe('0'));
    expect(screen.getByRole('status').textContent).toContain('Signed, position 1');
    mocks.board.mockRejectedValueOnce(new Error('refresh refused'));
    view.rerender(content(initial, 'en', 2));
    await waitFor(() => expect(mocks.error).toHaveBeenCalledWith(messages.DealsKanban.refreshFailed));
    expect(screen.getByTestId('deal-1').getAttribute('data-position')).toBe('0');
    expect(screen.getByRole('status').textContent).toContain('Signed, position 1');
});
