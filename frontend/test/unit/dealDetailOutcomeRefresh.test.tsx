/** @vitest-environment jsdom */

import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import type { MouseEvent, ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import DealActionsMenu from '@/app/components/records/deals/DealActionsMenu';
import { DealDetailProvider } from '@/app/components/records/deals/DealDetailState';
import { DealDetailStage, DealDetailStatus, DealDetailAmount, DealDetailClosedAt, DealDetailPerformance } from '@/app/components/records/deals/DealDetailValues';
import DealLifecycleProgress from '@/app/components/records/deals/DealLifecycleProgress';
import { NowProvider } from '@/app/hooks/useNow';
import { TooltipProvider } from '@/components/ui/tooltip';
import type { Deal, Stage } from '@/app/lib/types';
import en from '@/messages/en/deals.json';
import ja from '@/messages/ja/deals.json';

const requests = vi.hoisted(() => ({ close: vi.fn(), reopen: vi.fn(), refresh: vi.fn(), error: vi.fn() }));
vi.mock('@/app/lib/api', async (original) => ({ ...await original<typeof import('@/app/lib/api')>(), closeDeal: requests.close, reopenDeal: requests.reopen }));
vi.mock('next/navigation', () => ({ useRouter: () => ({ refresh: requests.refresh, push: vi.fn() }) }));
vi.mock('@/app/hooks/useApiErrorToast', () => ({ useApiErrorToast: () => requests.error }));
vi.mock('@/app/hooks/usePermissions', () => ({ usePermission: (permission: string) => permission === 'DEAL_UPDATE' }));
vi.mock('@/app/components/attachments/useAttachmentUploader', () => ({ useAttachmentUploader: () => ({ inputRef: { current: null }, uploading: false }) }));
vi.mock('@/app/hooks/useRecordTargetSearch', () => ({ useContactTargetSearch: () => ({}), useDealTargetSearch: () => ({}) }));
vi.mock('@/app/components/records/DeleteRecordDialog', () => ({ default: () => null }));
vi.mock('@/app/components/records/deals/EditDealSheet', () => ({ default: () => null }));
vi.mock('@/app/components/records/deals/DealTeamDialog', () => ({ default: () => null }));
vi.mock('@/app/components/activity/notes/NoteDialog', () => ({ default: () => null }));
vi.mock('@/app/components/records/RecordComposers', () => ({ RecordActivityComposer: () => null, RecordTaskComposer: () => null }));
vi.mock('@/components/ui/dropdown-menu', () => {
    const Group = ({ children }: { children?: ReactNode }) => <>{children}</>;
    return {
        DropdownMenu: Group, DropdownMenuContent: Group, DropdownMenuTrigger: Group, DropdownMenuSeparator: () => null,
        DropdownMenuItem: ({ children, onSelect, disabled }: { children?: ReactNode; onSelect?: (event: MouseEvent<HTMLButtonElement>) => void; disabled?: boolean }) => <button disabled={disabled} onClick={onSelect}>{children}</button>,
    };
});

const open: Deal = {
    id: 42, version: 1, workspaceId: 7, name: 'Proposal', value: 25400, actualValue: 0, currency: 'USD', pipeline: 1,
    stage: 2, company: null, position: 0, createdAt: '2026-11-01T12:00:00Z', updatedAt: '2026-11-01T12:00:00Z',
};
const won: Deal = { ...open, version: 2, won: true, actualValue: 28000, closedAt: '2026-11-07T12:00:00Z' };
const stages: Stage[] = [
    { id: 2, pipeline: 1, position: 0, name: 'Proposal', success: false, failure: false },
    { id: 3, pipeline: 1, position: 1, name: 'Won stage', success: true, failure: false },
];

function detail(initial = open, locale: 'en' | 'ja' = 'en') {
    return <NextIntlClientProvider locale={locale} messages={locale === 'en' ? en : ja} timeZone="UTC">
        <NowProvider value={Date.parse('2026-11-07T12:00:00Z')}><TooltipProvider>
            <DealDetailProvider key={`${initial.workspaceId}:${initial.id}`} initial={initial}>
                <section aria-label="Header"><DealDetailStatus /><DealDetailStage stages={stages} /><DealDetailAmount /></section>
                <dl><DealDetailStage stages={stages} row /><DealDetailClosedAt timezone="UTC" /></dl>
                <section aria-label="Performance"><DealDetailPerformance timezone="UTC" /></section>
                <section aria-label="Lifecycle"><DealLifecycleProgress stages={stages} timezone="UTC" currentStageId={initial.stage} outcome="open" createdAt={initial.createdAt} /></section>
                <DealActionsMenu timezone="UTC" deal={initial} pipelines={[]} stagesByPipeline={{ 1: stages }} currentUserId={1} />
            </DealDetailProvider>
        </TooltipProvider></NowProvider>
    </NextIntlClientProvider>;
}

beforeEach(() => { vi.clearAllMocks(); });
afterEach(cleanup);

describe('acknowledged deal outcome rendering', () => {
    it.each(['en', 'ja'] as const)('updates every outcome display while refresh is stalled and rejects older refresh data (%s)', async (locale) => {
        const copy = locale === 'en' ? en : ja;
        let finish: ((value: Deal) => void) | undefined;
        requests.close.mockImplementationOnce(() => new Promise<Deal>((resolve) => { finish = resolve; }));
        const view = render(detail(open, locale));
        const header = screen.getByRole('region', { name: 'Header' });
        fireEvent.click(screen.getByRole('button', { name: copy.DealsActionsMenu.markWon }));
        expect(requests.refresh).not.toHaveBeenCalled();
        expect(within(header).getByText(copy.DealsPage.statusOpen)).toBeTruthy();
        await act(async () => {
            if (!finish) throw new Error('Close request was not started');
            finish(won);
        });
        expect(requests.refresh).toHaveBeenCalledOnce();
        expect(within(header).getByText(copy.DealsPage.statusWon)).toBeTruthy();
        expect(header.textContent).toContain('28,000');
        expect(header.textContent).toContain('Proposal');
        const performance = screen.getByRole('region', { name: 'Performance' });
        expect(within(performance).getByText('+10.2%')).toBeTruthy();
        expect(performance.textContent).toContain(locale === 'ja' ? '2.8万' : '28');
        expect(screen.getByRole('region', { name: 'Lifecycle' }).textContent).toContain(copy.DealsLifecycleProgress.closed);
        view.rerender(detail({ ...open }, locale));
        expect(within(header).getByText(copy.DealsPage.statusWon)).toBeTruthy();
        const newer: Deal = { ...won, version: 3, actualValue: 30000, stage: 3 };
        view.rerender(detail(newer, locale));
        expect(header.textContent).toContain('30,000');
        expect(header.textContent).toContain('Won stage');
        expect(within(performance).getByText('+18.1%')).toBeTruthy();
        view.rerender(detail({ ...open, id: 43, workspaceId: 8 }, locale));
        const replacementHeader = screen.getByRole('region', { name: 'Header' });
        expect(within(replacementHeader).getByText(copy.DealsPage.statusOpen)).toBeTruthy();
        expect(replacementHeader.textContent).toContain('25,400');
    });

    it('keeps the original outcome and values when closing fails', async () => {
        requests.close.mockRejectedValueOnce(new Error('Refused'));
        render(detail());
        await act(async () => { fireEvent.click(screen.getByRole('button', { name: en.DealsActionsMenu.markWon })); });
        expect(within(screen.getByRole('region', { name: 'Header' })).getByText(en.DealsPage.statusOpen)).toBeTruthy();
        expect(requests.refresh).not.toHaveBeenCalled();
        expect(requests.error).toHaveBeenCalledOnce();
    });

    it('retains reopened server v3 after acknowledging won v2 and receiving delayed v1 props', async () => {
        requests.close.mockResolvedValueOnce(won);
        const view = render(detail());
        await act(async () => { fireEvent.click(screen.getByRole('button', { name: en.DealsActionsMenu.markWon })); });
        const header = screen.getByRole('region', { name: 'Header' });
        expect(within(header).getByText(en.DealsPage.statusWon)).toBeTruthy();

        const reopened: Deal = { ...open, version: 3 };
        view.rerender(detail(reopened));
        expect(within(header).getByText(en.DealsPage.statusOpen)).toBeTruthy();
        view.rerender(detail({ ...open }));

        expect(within(header).getByText(en.DealsPage.statusOpen)).toBeTruthy();
        expect(header.textContent).toContain('25,400');
        expect(within(screen.getByRole('region', { name: 'Performance' })).getAllByText('—')).toHaveLength(3);
    });

    it('publishes reopen responses and clears the actual amount without waiting for refresh', async () => {
        requests.reopen.mockResolvedValueOnce({ ...open, version: 3 });
        render(detail(won));
        await act(async () => { fireEvent.click(screen.getByRole('button', { name: en.DealsActionsMenu.markOpen })); });
        const header = screen.getByRole('region', { name: 'Header' });
        expect(within(header).getByText(en.DealsPage.statusOpen)).toBeTruthy();
        expect(header.textContent).toContain('25,400');
        expect(within(screen.getByRole('region', { name: 'Performance' })).getAllByText('—')).toHaveLength(3);
    });
});
