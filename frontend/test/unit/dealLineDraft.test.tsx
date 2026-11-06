/** @vitest-environment jsdom */

import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';
import { DealLineItemsSection } from '@/app/components/records/DetailCollections';
import { DetailSectionsProvider } from '@/app/hooks/useDetailSections';
import enRecords from '@/messages/en/records.json';
import enCommon from '@/messages/en/common.json';
import jaRecords from '@/messages/ja/records.json';
import jaCommon from '@/messages/ja/common.json';
import DealLineItems from '@/app/components/records/deals/DealLineItems';
import type { DealLineItem, DealLineItemsResponse } from '@/app/lib/types';
import { ApiError } from '@/app/lib/api';
import en from '@/messages/en/deals.json';
import { bindRenderedWorkspace, releaseRenderedWorkspace } from '@/app/lib/renderedWorkspace';
import ja from '@/messages/ja/deals.json';

const api = vi.hoisted(() => ({ read: vi.fn(), create: vi.fn(), update: vi.fn(), remove: vi.fn(), products: vi.fn(), refresh: vi.fn(), error: vi.fn() }));
vi.mock('next/navigation', () => ({ useRouter: () => ({ refresh: api.refresh }), usePathname: () => '/' }));
vi.mock('@/app/hooks/useWorkspace', () => ({ useWorkspace: () => ({ activeWorkspaceId: 9 }) }));
vi.mock('@/app/hooks/useApiErrorToast', () => ({ useApiErrorToast: () => api.error }));
vi.mock('@/app/lib/api', async (importOriginal) => {
    const actual = await importOriginal<typeof import('@/app/lib/api')>();
    return { ...actual, getDealLineItems: api.read, getProducts: api.products, createDealLineItem: api.create,
        updateDealLineItem: api.update, deleteDealLineItem: api.remove };
});

const totals = { currency: 'USD', subtotal: 120.5, tax: 12.05, oneTimeTotal: 132.55, recurringTotal: 0, grandTotal: 132.55 };
const line: DealLineItem = { id: 2, dealId: 7, name: 'Service', unitPrice: 120.5, quantity: 1,
    taxRate: 10, billingFrequency: 'one_time', position: 0, currency: 'USD', lineSubtotal: 120.5,
    lineTax: 12.05, lineTotal: 132.55, createdAt: '', updatedAt: '' };
const empty: DealLineItemsResponse = { items: [], totals: { ...totals, subtotal: 0, tax: 0, grandTotal: 0, oneTimeTotal: 0 } };

function mount(initial = empty, locale = 'en', currency = 'USD') {
    return render(<NextIntlClientProvider locale={locale} messages={locale === 'ja' ? ja : en}>
        <DealLineItems dealId={7} dealWorkspaceId={9} dealCurrency={currency} initial={initial} />
    </NextIntlClientProvider>);
}

const shell = {};
beforeEach(() => { vi.clearAllMocks(); api.read.mockReset(); api.products.mockResolvedValue([]);
    window.history.replaceState(null, '', '/'); bindRenderedWorkspace(shell, 9); });
afterEach(() => { cleanup(); releaseRenderedWorkspace(shell); });

async function pricedDraft() {
    fireEvent.click(screen.getByRole('button', { name: 'Add line' }));
    fireEvent.change(screen.getByRole('spinbutton', { name: 'Unit price' }), { target: { value: '120.50' } });
    fireEvent.change(screen.getByRole('spinbutton', { name: 'Tax rate (%)' }), { target: { value: '10' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save line' }));
    await screen.findByRole('dialog');
}

describe('line value consent', () => {
    it('keeps the standalone editor landmark when no recovery wrapper owns it', () => {
        mount();
        expect(screen.getAllByRole('region', { name: en.DealsLineItems.title })).toHaveLength(1);
    });

    it.each(['en', 'ja'])('keeps Add line and discard local in %s', async (locale) => {
        mount(empty, locale);
        const t = locale === 'ja' ? ja.DealsLineItems : en.DealsLineItems;
        fireEvent.click(screen.getByRole('button', { name: t.addLine }));
        expect(api.create).not.toHaveBeenCalled();
        expect(screen.getByRole('spinbutton', { name: t.columnUnitPrice }).getAttribute('value')).toBe('');
        expect(screen.getByRole('button', { name: t.saveLine }).hasAttribute('disabled')).toBe(true);
        expect(api.create).not.toHaveBeenCalled();
        fireEvent.click(screen.getByRole('button', { name: t.discardDraft }));
        expect(api.create).not.toHaveBeenCalled();
        expect(api.refresh).not.toHaveBeenCalled();
    });

    it('previews exact tax-inclusive totals, preserves draft on cancel, and sends consent only on confirmation', async () => {
        api.create.mockResolvedValue({ items: [line], totals, manualValueToReplace: 121600 });
        mount();
        await pricedDraft();
        expect(screen.getByRole('dialog').textContent).toContain('$121,600.00 to $132.55, including tax');
        expect(api.create).toHaveBeenCalledTimes(1);
        expect(api.create).toHaveBeenLastCalledWith(7, expect.objectContaining({ previewOnly: true, unitPrice: 120.5, taxRate: 10 }), expect.any(Object));
        fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
        expect(screen.queryByRole('dialog')).toBeNull();
        expect(screen.getByRole('spinbutton', { name: 'Unit price' }).getAttribute('value')).toBe('120.5');
        expect(api.refresh).not.toHaveBeenCalled();
        fireEvent.click(screen.getByRole('button', { name: 'Save line' }));
        await screen.findByRole('dialog');
        api.create.mockResolvedValue({ items: [line], totals, restorableManualValue: 121600 });
        fireEvent.click(screen.getByRole('button', { name: 'Replace value and save' }));
        await waitFor(() => expect(api.refresh).toHaveBeenCalledOnce());
        expect(api.create).toHaveBeenLastCalledWith(7, expect.objectContaining({ confirmValueReplacement: true, expectedManualValue: 121600 }), expect.any(Object));
        expect(screen.queryByRole('form', { name: 'New line item' })).toBeNull();
        expect(screen.getByText('Removing the last line will restore the manual deal value of $121,600.00.')).toBeTruthy();
    });

    it('shows fractional JPY amounts exactly in the Japanese confirmation', async () => {
        api.create.mockResolvedValue({ items: [line], totals: { ...totals, currency: 'JPY' }, manualValueToReplace: 121600 });
        mount(empty, 'ja', 'JPY');
        fireEvent.click(screen.getByRole('button', { name: ja.DealsLineItems.addLine }));
        fireEvent.change(screen.getByRole('spinbutton', { name: ja.DealsLineItems.columnUnitPrice }), { target: { value: '120.50' } });
        fireEvent.click(screen.getByRole('button', { name: ja.DealsLineItems.saveLine }));
        const dialog = await screen.findByRole('dialog');
        expect(dialog.textContent).toContain('132.55');
        expect(dialog.textContent).toContain('税込み');
    });

    it('keeps supported custom currency codes readable in confirmation', async () => {
        api.create.mockResolvedValue({ items: [line], totals: { ...totals, currency: 'USDT' }, manualValueToReplace: 121600 });
        mount(empty, 'en', 'USDT');
        await pricedDraft();
        expect(screen.getByRole('dialog').textContent).toContain('USDT 132.55');
    });

    it.each(['DEAL_LINE_VALUE_CONSENT_REQUIRED', 'DEAL_LINE_VALUE_CONSENT_STALE'])('keeps the draft on %s and offers review again', async (code) => {
        api.create.mockResolvedValueOnce({ items: [line], totals, manualValueToReplace: 121600 });
        api.create.mockRejectedValueOnce(new ApiError('Conflict', 409, code));
        mount();
        await pricedDraft();
        fireEvent.click(screen.getByRole('button', { name: 'Replace value and save' }));
        await waitFor(() => expect(api.error).toHaveBeenCalledOnce());
        expect(screen.getByRole('form', { name: 'New line item' })).toBeTruthy();
        expect(api.refresh).not.toHaveBeenCalled();
        expect(screen.getByRole('spinbutton', { name: 'Unit price' }).getAttribute('value')).toBe('120.5');
        api.create.mockResolvedValue({ items: [line], totals, manualValueToReplace: 121600 });
        fireEvent.click(screen.getByRole('button', { name: 'Save line' }));
        await screen.findByRole('dialog');
    });

    it('persists editable tax without repeated consent on a line-derived deal', async () => {
        api.update.mockResolvedValue({ items: [{ ...line, taxRate: 8 }], totals });
        mount({ items: [line], totals });
        const input = screen.getByRole('spinbutton', { name: 'Tax rate (%)' });
        fireEvent.change(input, { target: { value: '8' } });
        fireEvent.blur(input);
        await waitFor(() => expect(api.refresh).toHaveBeenCalledOnce());
        expect(api.update).toHaveBeenLastCalledWith(7, 2, expect.objectContaining({ taxRate: 8 }), expect.any(Object));
        expect(screen.queryByRole('dialog')).toBeNull();
    });

    it('persists a discount edit with its type, tax and service period intact', async () => {
        const discounted = { ...line, discountType: 'percent' as const, discountValue: 10,
            servicePeriodStart: '2026-11-01', servicePeriodEnd: '2026-11-30' };
        api.update.mockResolvedValue({ items: [{ ...discounted, discountValue: 20 }], totals });
        mount({ items: [discounted], totals });
        const input = screen.getByRole('spinbutton', { name: 'Discount value' });
        fireEvent.change(input, { target: { value: '20' } });
        fireEvent.blur(input);
        await waitFor(() => expect(api.refresh).toHaveBeenCalledOnce());
        expect(api.update).toHaveBeenLastCalledWith(7, 2, expect.objectContaining({
            discountType: 'percent', discountValue: 20, taxRate: 10,
            servicePeriodStart: '2026-11-01', servicePeriodEnd: '2026-11-30',
        }), expect.any(Object));
    });

    it('blocks a percentage above 100 on an existing line without saving it', () => {
        mount({ items: [{ ...line, discountType: 'percent', discountValue: 20 }], totals });
        const input = screen.getByRole('spinbutton', { name: 'Discount value' });
        expect(input.getAttribute('max')).toBe('100');
        fireEvent.change(input, { target: { value: '200' } });
        fireEvent.blur(input);
        expect(api.update).not.toHaveBeenCalled();
    });

    it('keeps fixed-amount discount bounds separate from percentages', () => {
        mount({ items: [{ ...line, discountType: 'amount', discountValue: 200 }], totals });
        expect(screen.getByRole('spinbutton', { name: 'Discount value' }).getAttribute('max')).toBeNull();
    });
});


describe.each(['en', 'ja'] as const)('line-item section landmarks in %s', (locale) => {
    const messages = locale === 'ja' ? { ...ja, ...jaRecords, ...jaCommon } : { ...en, ...enRecords, ...enCommon };
    const labels = locale === 'ja' ? ja.DealsLineItems : en.DealsLineItems;
    const recovery = locale === 'ja' ? jaRecords.RecordListState : enRecords.RecordListState;

    it.each([{ name: 'empty', data: empty }, { name: 'populated', data: { items: [line], totals } }])('exposes one line-items landmark for a loaded $name section', ({ data }) => {
        render(<NextIntlClientProvider locale={locale} messages={messages}>
            <DetailSectionsProvider recordId={7} workspaceId={9} initial={{ dealLineItems: { kind: 'loaded', data } }}>
                <DealLineItemsSection dealId={7} dealWorkspaceId={9} dealCurrency="USD" />
            </DetailSectionsProvider>
        </NextIntlClientProvider>);
        expect(screen.getAllByRole('region', { name: labels.title })).toHaveLength(1);
        expect(within(screen.getByRole('region', { name: labels.title })).getByRole('button', { name: labels.addLine })).toBeDefined();
    });

    it.each([403, 503])('keeps one line-items landmark through HTTP %s failure, pending retry and recovery', async (status) => {
        let finish: ((value: DealLineItemsResponse) => void) | undefined;
        api.read.mockImplementationOnce(() => new Promise<DealLineItemsResponse>((resolve) => { finish = resolve; }));
        render(<NextIntlClientProvider locale={locale} messages={messages}>
            <DetailSectionsProvider recordId={7} workspaceId={9} initial={{ dealLineItems: { kind: 'failed', status } }}>
                <DealLineItemsSection dealId={7} dealWorkspaceId={9} dealCurrency="USD" />
            </DetailSectionsProvider>
        </NextIntlClientProvider>);
        const landmark = screen.getByRole('region', { name: labels.title });
        const retry = within(landmark).getByRole('button', { name: recovery.retry });
        retry.focus();
        fireEvent.click(retry);
        expect(screen.getAllByRole('region', { name: labels.title })).toHaveLength(1);
        expect(within(landmark).getByRole('button', { name: recovery.retrying }).hasAttribute('disabled')).toBe(true);
        await act(async () => { finish?.(empty); });
        await screen.findByRole('button', { name: labels.addLine });
        expect(screen.getAllByRole('region', { name: labels.title })).toHaveLength(1);
        expect(screen.getByRole('region', { name: labels.title })).toBe(landmark);
        expect(document.activeElement).toBe(landmark);
    });
});
