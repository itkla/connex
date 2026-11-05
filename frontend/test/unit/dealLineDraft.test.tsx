/** @vitest-environment jsdom */

import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';
import DealLineItems from '@/app/components/records/deals/DealLineItems';
import type { DealLineItem, DealLineItemsResponse } from '@/app/lib/types';
import en from '@/messages/en/deals.json';
import ja from '@/messages/ja/deals.json';

const api = vi.hoisted(() => ({ create: vi.fn(), update: vi.fn(), remove: vi.fn(), products: vi.fn(), refresh: vi.fn(), error: vi.fn() }));
vi.mock('next/navigation', () => ({ useRouter: () => ({ refresh: api.refresh }) }));
vi.mock('@/app/hooks/useApiErrorToast', () => ({ useApiErrorToast: () => api.error }));
vi.mock('@/app/lib/api', () => ({ getProducts: api.products, createDealLineItem: api.create,
    updateDealLineItem: api.update, deleteDealLineItem: api.remove }));

const totals = { currency: 'USD', subtotal: 120.5, tax: 12.05, oneTimeTotal: 132.55, recurringTotal: 0, grandTotal: 132.55 };
const line: DealLineItem = { id: 2, dealId: 7, name: 'Service', unitPrice: 120.5, quantity: 1,
    taxRate: 10, billingFrequency: 'one_time', position: 0, currency: 'USD', lineSubtotal: 120.5,
    lineTax: 12.05, lineTotal: 132.55, createdAt: '', updatedAt: '' };
const empty: DealLineItemsResponse = { items: [], totals: { ...totals, subtotal: 0, tax: 0, grandTotal: 0, oneTimeTotal: 0 } };

function mount(initial = empty, locale = 'en', currency = 'USD') {
    return render(<NextIntlClientProvider locale={locale} messages={locale === 'ja' ? ja : en}>
        <DealLineItems dealId={7} dealCurrency={currency} initial={initial} />
    </NextIntlClientProvider>);
}

beforeEach(() => { vi.clearAllMocks(); api.products.mockResolvedValue([]); });
afterEach(cleanup);

async function pricedDraft() {
    fireEvent.click(screen.getByRole('button', { name: 'Add line' }));
    fireEvent.change(screen.getByRole('spinbutton', { name: 'Unit price' }), { target: { value: '120.50' } });
    fireEvent.change(screen.getByRole('spinbutton', { name: 'Tax rate (%)' }), { target: { value: '10' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save line' }));
    await screen.findByRole('dialog');
}

describe('line value consent', () => {
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
        expect(api.create).toHaveBeenLastCalledWith(7, expect.objectContaining({ previewOnly: true, unitPrice: 120.5, taxRate: 10 }));
        fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
        expect(screen.queryByRole('dialog')).toBeNull();
        expect(screen.getByRole('spinbutton', { name: 'Unit price' }).getAttribute('value')).toBe('120.5');
        expect(api.refresh).not.toHaveBeenCalled();
        fireEvent.click(screen.getByRole('button', { name: 'Save line' }));
        await screen.findByRole('dialog');
        api.create.mockResolvedValue({ items: [line], totals, restorableManualValue: 121600 });
        fireEvent.click(screen.getByRole('button', { name: 'Replace value and save' }));
        await waitFor(() => expect(api.refresh).toHaveBeenCalledOnce());
        expect(api.create).toHaveBeenLastCalledWith(7, expect.objectContaining({ confirmValueReplacement: true, expectedManualValue: 121600 }));
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

    it('keeps the draft on a rejected takeover', async () => {
        api.create.mockResolvedValueOnce({ items: [line], totals, manualValueToReplace: 121600 });
        api.create.mockRejectedValueOnce(new Error('Conflict'));
        mount();
        await pricedDraft();
        fireEvent.click(screen.getByRole('button', { name: 'Replace value and save' }));
        await waitFor(() => expect(api.error).toHaveBeenCalledOnce());
        expect(screen.getByRole('form', { name: 'New line item' })).toBeTruthy();
        expect(api.refresh).not.toHaveBeenCalled();
    });

    it('persists editable tax without repeated consent on a line-derived deal', async () => {
        api.update.mockResolvedValue({ items: [{ ...line, taxRate: 8 }], totals });
        mount({ items: [line], totals });
        const input = screen.getByRole('spinbutton', { name: 'Tax rate (%)' });
        fireEvent.change(input, { target: { value: '8' } });
        fireEvent.blur(input);
        await waitFor(() => expect(api.refresh).toHaveBeenCalledOnce());
        expect(api.update).toHaveBeenLastCalledWith(7, 2, expect.objectContaining({ taxRate: 8 }));
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
        }));
    });
});
