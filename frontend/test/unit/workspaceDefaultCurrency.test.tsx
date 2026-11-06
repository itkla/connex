/** @vitest-environment jsdom */
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ComponentProps } from 'react';

import DealCreateContainer from '@/app/components/actions/create/DealCreateContainer';
import CalendarNewDealContainer from '@/app/components/calendar/CalendarNewDealContainer';
import WorkspaceCurrencyPanel from '@/app/components/settings/WorkspaceCurrencyPanel';
import WorkspaceIdentityPanel from '@/app/components/settings/WorkspaceIdentityPanel';
import { ApiError } from '@/app/lib/api';
import { workspaceDefaultCurrency } from '@/app/lib/currencies';
import type { Workspace } from '@/app/lib/types';
import type NewDealDialog from '@/app/components/records/deals/NewDealDialog';
import en from '@/messages/en/workspace.json';
import ja from '@/messages/ja/workspace.json';

const workspace: Workspace = {
    id: 7, name: 'Sales', slug: 'sales', timezone: null, defaultCurrency: 'EUR', identityVersion: 2,
    role: 'admin', orgId: 3, orgName: 'Organization', orgIdentityVersion: 0, orgRole: null,
};
const state = vi.hoisted(() => ({ currency: 'EUR' as string | null }));
const api = vi.hoisted(() => ({ create: vi.fn(), save: vi.fn(), publish: vi.fn(), refresh: vi.fn() }));
const choices = vi.hoisted(() => ({ scope: {}, pipelines: [], stagesByPipeline: {}, setPipelines: vi.fn(), setStagesByPipeline: vi.fn() }));
vi.mock('next/navigation', () => ({ useRouter: () => ({ refresh: api.refresh }) }));
vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key, useLocale: () => 'en' }));
vi.mock('@/app/hooks/useWorkspace', () => ({ useWorkspace: () => ({
    activeWorkspaceId: 7, activeWorkspace: { ...workspace, defaultCurrency: state.currency }, publishWorkspaceIdentity: api.publish,
}) }));
vi.mock('@/app/hooks/usePermissions', () => ({ usePermissionCheck: () => 'granted', usePermissionsRefresh: () => vi.fn() }));
vi.mock('@/app/hooks/usePipelineChoices', () => ({ usePipelineChoices: () => choices }));
vi.mock('@/app/hooks/useFormDraft', () => ({ useFormDraft: () => ({ persist: vi.fn(), clear: vi.fn() }) }));
vi.mock('@/app/hooks/useApiErrorToast', () => ({ useApiErrorToast: () => vi.fn() }));
vi.mock('@/app/hooks/usePasskeyStepUpError', () => ({ usePasskeyStepUpErrorHandler: () => () => false }));
vi.mock('@/app/lib/toast', () => ({ toastSuccess: vi.fn(), toastError: vi.fn(), toastWarn: vi.fn() }));
vi.mock('@/app/lib/renderedWorkspace', () => ({
    renderedWorkspaceId: () => 7,
    workspacePinnedRequest: () => ({ headers: { 'X-Workspace-Id': '7' } }),
}));
vi.mock('@/app/lib/api', async (original) => ({
    ...await original<typeof import('@/app/lib/api')>(),
    createDeal: api.create, updateWorkspaceDefaultCurrency: api.save,
    getPipelines: async () => [], getStagesByPipelineId: async () => [],
}));
vi.mock('@/app/components/records/deals/NewDealDialog', async (original) => ({
    ...await original<typeof import('@/app/components/records/deals/NewDealDialog')>(),
    default: ({ payload, setPayload, createNewDeal }: ComponentProps<typeof NewDealDialog>) => <>
        <output data-testid="currency">{payload.currency}</output>
        <output data-testid="date">{payload.expectedCloseDate}</output>
        <input aria-label="name" value={payload.name} onChange={(event) => setPayload({ ...payload, name: event.target.value })} />
        <button onClick={() => void createNewDeal('proof')}>submit</button>
    </>,
}));

beforeEach(() => {
    vi.clearAllMocks();
    state.currency = 'EUR';
    api.create.mockResolvedValue({});
    api.save.mockResolvedValue({ ...workspace, defaultCurrency: 'USD', identityVersion: 3 });
});
afterEach(cleanup);

describe('workspace default deal currency', () => {
    it('uses only the explicit setting or the explained historical fallback', () => {
        expect(workspaceDefaultCurrency({ defaultCurrency: 'EUR' })).toBe('EUR');
        expect(workspaceDefaultCurrency({ defaultCurrency: null })).toBe('USD');
        expect(workspaceDefaultCurrency(undefined)).toBe('USD');
    });

    it('quick create starts and submits in the saved currency', async () => {
        render(<DealCreateContainer open onOpenChange={() => undefined} />);
        await waitFor(() => expect(screen.getByTestId('currency').textContent).toBe('EUR'));
        fireEvent.click(screen.getByText('submit'));
        await waitFor(() => expect(api.create).toHaveBeenCalledWith(expect.objectContaining({ currency: 'EUR' }), expect.anything()));
    });

    it('calendar create starts and submits in the saved currency with its selected date', async () => {
        render(<CalendarNewDealContainer open onOpenChange={() => undefined} defaultExpectedCloseDate="2027-01-05" />);
        await waitFor(() => expect(screen.getByTestId('currency').textContent).toBe('EUR'));
        await waitFor(() => expect(screen.getByTestId('date').textContent).toBe('2027-01-05'));
        fireEvent.click(screen.getByText('submit'));
        await waitFor(() => expect(api.create).toHaveBeenCalledWith(expect.objectContaining({ currency: 'EUR', expectedCloseDate: '2027-01-05' }), expect.anything()));
    });

    it.each(['quick', 'calendar'])('a settings refresh preserves an open %s draft; reopening uses the new default', async (entry) => {
        const view = (open: boolean) => entry === 'quick'
            ? <DealCreateContainer open={open} onOpenChange={() => undefined} />
            : <CalendarNewDealContainer open={open} onOpenChange={() => undefined} defaultExpectedCloseDate="2027-01-05" />;
        const rendered = render(view(true));
        await act(async () => { await new Promise<void>((resolve) => window.requestAnimationFrame(() => resolve())); });
        fireEvent.change(screen.getByRole('textbox', { name: 'name' }), { target: { value: 'Keep this renewal' } });
        state.currency = 'GBP';
        rendered.rerender(view(true));
        await act(async () => { await new Promise<void>((resolve) => window.requestAnimationFrame(() => resolve())); });
        expect(screen.getByRole('textbox', { name: 'name' })).toHaveProperty('value', 'Keep this renewal');
        expect(screen.getByTestId('currency').textContent).toBe('EUR');
        rendered.rerender(view(false));
        rendered.rerender(view(true));
        await waitFor(() => expect(screen.getByTestId('currency').textContent).toBe('GBP'));
    });

    it('shows the unsaved fallback and allows explicitly choosing that same effective default', async () => {
        render(<WorkspaceCurrencyPanel workspace={{ ...workspace, defaultCurrency: null }} />);
        expect(screen.getByText('currencyFallback')).toBeTruthy();
        fireEvent.click(screen.getByRole('button', { name: 'currencySave' }));
        await waitFor(() => expect(api.save).toHaveBeenCalledWith(7, 'USD', 2));
        await waitFor(() => expect(api.publish).toHaveBeenCalledWith(expect.objectContaining({ defaultCurrency: 'USD', identityVersion: 3 })));
    });

    it('keeps the workspace identity and currency save actions distinct in both languages', () => {
        state.currency = null;
        render(<WorkspaceIdentityPanel />);
        const identitySave = screen.getByRole('button', { name: 'save' });
        const currencySave = screen.getByRole('button', { name: 'currencySave' });
        expect(identitySave.closest('form')).not.toBe(currencySave.closest('form'));
        for (const catalog of [en, ja]) {
            expect(catalog.WorkspaceIdentity.currencySave).not.toBe(catalog.WorkspaceIdentity.save);
        }
    });

    it('retains the selected currency and reports a localized field error after refusal', async () => {
        api.save.mockRejectedValue(new ApiError('Unsupported', 400, undefined, { defaultCurrency: 'diagnostic' }));
        render(<WorkspaceCurrencyPanel workspace={{ ...workspace, defaultCurrency: null }} />);
        fireEvent.click(screen.getByRole('button', { name: 'currencySave' }));
        await waitFor(() => expect(screen.getByRole('alert').textContent).toBe('currencyInvalid'));
        expect(api.publish).not.toHaveBeenCalled();
    });
});
