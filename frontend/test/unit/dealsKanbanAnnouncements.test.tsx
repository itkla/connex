/** @vitest-environment jsdom */
import { act, type ComponentProps, type ReactNode } from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { DndContext, type DragEndEvent } from '@dnd-kit/core';
import { NextIntlClientProvider } from 'next-intl';
import { afterEach, beforeEach, expect, it, vi, type Mock } from 'vitest';
import DealsKanban from '@/app/components/records/deals/DealsKanban';
import type { Deal, Pipeline, Stage } from '@/app/lib/types';
import en from '@/messages/en/deals.json';
import ja from '@/messages/ja/deals.json';
import enCommon from '@/messages/en/common.json';
import jaCommon from '@/messages/ja/common.json';

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

const mocks = vi.hoisted((): {
    props?: ComponentProps<typeof DndContext>;
    move: Mock<() => Promise<Deal>>;
    error: Mock;
    success: Mock;
    scrollRef: () => void;
} => ({ move: vi.fn(), error: vi.fn(), success: vi.fn(), scrollRef: () => undefined }));
vi.mock('@dnd-kit/core', async (original) => {
    const core = await original<typeof import('@dnd-kit/core')>();
    return { ...core, DndContext: (props: ComponentProps<typeof DndContext>) => {
        mocks.props = props;
        return <core.DndContext {...props} />;
    } };
});
vi.mock('@/app/hooks/useDragScroll', () => ({ useDragScroll: () => ({ ref: mocks.scrollRef, edges: { left: false, right: false } }) }));
vi.mock('@/components/ui/icon-button', () => ({ IconButton: ({ label, children, ...props }: ComponentProps<'button'> & { label: string; children: ReactNode }) => <button aria-label={label} {...props}>{children}</button> }));
vi.mock('@/app/components/records/deals/DealKanbanCard', () => ({ default: ({ deal, dragHandle }: { deal: Deal; dragHandle?: ReactNode }) => <div>{dragHandle}{deal.name}</div> }));
vi.mock('@/app/hooks/usePermissions', () => ({ usePermission: () => true }));
vi.mock('@/app/hooks/useApiErrorToast', () => ({ useApiErrorToast: () => mocks.error }));
vi.mock('@/app/lib/renderedWorkspace', () => ({ renderedWorkspaceId: () => 7, workspacePinnedRequest: () => ({ headers: { 'X-Workspace-Id': '7' } }) }));
vi.mock('@/app/lib/toast', () => ({ toastSuccess: mocks.success, toastError: mocks.error }));
vi.mock('@/app/lib/api', () => ({
    getDealBoard: async () => [initial], getCompaniesByIds: async () => [], getDealRisks: async () => [], moveDeal: mocks.move,
}));

const initial: Deal = { id: 1, name: 'Example', pipeline: 1, stage: 10, position: 4, won: null, value: 100, actualValue: 0, currency: 'JPY', company: null, createdAt: '', updatedAt: '' };
const pipeline: Pipeline = { id: 1, name: 'Sales', createdAt: '', updatedAt: '' };
const stages: Stage[] = [
    { id: 10, pipeline: 1, name: 'Proposal', position: 0, success: false, failure: false },
    { id: 20, pipeline: 1, name: 'Signed', position: 1, success: true, failure: false },
];
function drag(): DragEndEvent {
    return {
        active: { id: 1, data: { current: {} }, rect: { current: { initial: null, translated: null } } },
        over: { id: 'col:20', data: { current: {} }, disabled: false, rect: { top: 0, left: 0, right: 10, bottom: 10, width: 10, height: 10 } },
        activatorEvent: new KeyboardEvent('keydown'), collisions: null, delta: { x: 0, y: 0 },
    };
}
function mount(locale: 'en' | 'ja') {
    return render(<NextIntlClientProvider locale={locale} timeZone="UTC" messages={locale === 'en' ? { ...enCommon, ...en } : { ...jaCommon, ...ja }}>
        <DealsKanban timezone="UTC" deals={[initial]} pipelines={[pipeline]} stagesByPipeline={{ 1: stages }} companyById={new Map()}
            pipelineById={new Map([[1, pipeline]])} stageById={new Map(stages.map((stage) => [stage.id, stage]))} riskByDealId={new Map()}
            onMoved={vi.fn()} query="" filters={{}} segmentActive={false} segmentIds={null} segmentLoading={false} segmentError={null}
            currentUserId={1} revision={1} reduce />
    </NextIntlClientProvider>);
}

beforeEach(() => vi.clearAllMocks());
afterEach(cleanup);

for (const locale of ['en', 'ja'] as const) {
    const catalog = locale === 'en' ? en.DealsKanban : ja.DealsKanban;
    for (const result of ['saved', 'cancelled', 'refused'] as const) {
        it(`${locale}: owns the request and ${result} feedback in one live region`, async () => {
            let save: ((deal: Deal) => void) | undefined;
            let refuse: ((error: Error) => void) | undefined;
            mocks.move.mockImplementation(() => new Promise<Deal>((resolve, reject) => { save = resolve; refuse = reject; }));
            mount(locale);
            await screen.findByRole('button', { name: catalog.moveCard.replace('{name}', 'Example') });
            const live = document.querySelector('[data-kanban-announcement]');
            expect(live).not.toBeNull();
            const event = drag();
            await act(async () => mocks.props?.onDragStart?.(event));
            await act(async () => mocks.props?.onDragOver?.(event));
            await act(async () => mocks.props?.onDragEnd?.(event));
            const request = catalog.a11yConfirmMove.replace('{name}', 'Example').replace('{column}', 'Signed').replace('{outcome}', catalog.outcome.won);
            expect(live?.textContent).toBe(request);
            expect(mocks.move).not.toHaveBeenCalled();
            fireEvent.click(screen.getByRole('button', { name: result === 'cancelled' ? catalog.cancelMove : catalog.confirmMove }));
            await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
            expect(screen.getAllByRole('status', { hidden: true }).some((status) => status.textContent === request)).toBe(false);
            if (result !== 'cancelled') {
                await waitFor(() => expect(mocks.move).toHaveBeenCalledOnce());
                if (result === 'saved') await act(async () => save?.({ ...initial, stage: 20, won: true, position: 2 }));
                else await act(async () => refuse?.(new Error('refused')));
            }
            const final = result === 'saved'
                ? catalog.moved.replace('{name}', 'Example').replace('{column}', 'Signed').replace('{position}', '3').replace('{outcome}', catalog.outcome.won)
                : catalog.a11yRestored.replace('{name}', 'Example').replace('{column}', 'Proposal').replace('{position}', '1');
            await waitFor(() => expect(live?.textContent).toBe(final));
            expect(screen.getAllByRole('status', { hidden: true }).filter((status) => status.textContent)).toEqual([live]);
            expect(mocks.success).not.toHaveBeenCalled();
        });
    }
}
