import { act, type ButtonHTMLAttributes, type InputHTMLAttributes, type PropsWithChildren } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import DealLineItems from '@/app/components/records/deals/DealLineItems';
import type { DealLineItem, DealLineItemsResponse, Product } from '@/app/lib/types';
import en from '@/messages/en/deals.json';
import ja from '@/messages/ja/deals.json';
import { installInteractiveDocument } from '@/test/unit/helpers/interactiveDocument';

const state = vi.hoisted(() => ({ locale: 'en' }));
const api = vi.hoisted(() => ({
    getProducts: vi.fn<() => Promise<Product[]>>(),
    deleteLine: vi.fn<(dealId: number, itemId: number) => Promise<DealLineItemsResponse>>(),
    refresh: vi.fn(),
}));

vi.mock('next/navigation', () => ({ useRouter: () => ({ refresh: api.refresh }) }));
vi.mock('next-intl', async () => {
    const { createTranslator } = await vi.importActual<typeof import('next-intl')>('next-intl');
    return {
        useLocale: () => state.locale,
        useTranslations: () => createTranslator({
            locale: state.locale,
            messages: state.locale === 'ja' ? ja.DealsLineItems : en.DealsLineItems,
        }),
    };
});
vi.mock('@/app/hooks/useApiErrorToast', () => ({ useApiErrorToast: () => vi.fn() }));
vi.mock('@/app/lib/api', () => ({
    getProducts: api.getProducts,
    deleteDealLineItem: api.deleteLine,
    createDealLineItem: vi.fn(),
    updateDealLineItem: vi.fn(),
}));
vi.mock('@/components/ui/button', () => ({
    Button: ({ variant, size, ...props }: ButtonHTMLAttributes<HTMLButtonElement> & {
        variant?: string;
        size?: string;
    }) => <button {...props} data-variant={variant} data-size={size} />,
}));
vi.mock('@/components/ui/input', () => ({
    Input: (props: InputHTMLAttributes<HTMLInputElement>) => <input {...props} />,
}));
vi.mock('@/components/ui/combobox', () => {
    const Container = ({ children }: PropsWithChildren) => <div>{children}</div>;
    return {
        Combobox: Container,
        ComboboxContent: Container,
        ComboboxEmpty: Container,
        ComboboxInput: () => null,
        ComboboxItem: Container,
        ComboboxList: Container,
    };
});

function foreignLine(id: number): DealLineItem {
    return {
        id,
        dealId: 7,
        name: `JPY service ${id}`,
        unitPrice: 100020,
        quantity: 1,
        discountType: 'amount',
        discountValue: 20,
        billingFrequency: 'one_time',
        position: id,
        currency: 'JPY',
        lineSubtotal: 100000,
        lineTax: 0,
        lineTotal: 100000,
        createdAt: '2026-09-15T00:00:00Z',
        updatedAt: '2026-09-15T00:00:00Z',
    };
}

const mountedRoots: Array<{ unmount: () => void }> = [];

async function mount(initial: DealLineItemsResponse) {
    const { createRoot } = await import('react-dom/client');
    const installed = installInteractiveDocument();
    const root = createRoot(installed.container);
    mountedRoots.push(root);
    await act(async () => root.render(<DealLineItems dealId={7} dealCurrency="USD" initial={initial} />));
    return installed;
}

beforeEach(() => {
    state.locale = 'en';
    api.getProducts.mockReset().mockResolvedValue([]);
    api.deleteLine.mockReset();
    api.refresh.mockReset();
});

afterEach(async () => {
    for (const root of mountedRoots.splice(0)) await act(async () => root.unmount());
    vi.unstubAllGlobals();
});

describe('deal line currency repair', () => {
    it.each(['en', 'ja'])('shows unavailable totals and original line currency on initial load in %s', async (locale) => {
        state.locale = locale;
        const mounted = await mount({ items: [foreignLine(1)], totals: null });
        const messages = locale === 'ja' ? ja.DealsLineItems : en.DealsLineItems;
        const text = mounted.container.textContent;

        expect(text).toContain(messages.totalsUnavailable);
        expect(text).toContain(messages.currencyMismatch.replaceAll('{lineCurrency}', 'JPY').replaceAll('{dealCurrency}', 'USD'));
        expect(text).toContain(new Intl.NumberFormat(locale, { style: 'currency', currency: 'JPY' }).format(100000));
        expect(text).toContain(new Intl.NumberFormat(locale, { style: 'currency', currency: 'JPY' }).format(20));
        expect(text).not.toContain(new Intl.NumberFormat(locale, { style: 'currency', currency: 'USD' }).format(100000));
        expect(text).not.toContain(new Intl.NumberFormat(locale, { style: 'currency', currency: 'USD' }).format(0));
        expect(mounted.elements.some((element) => element.tagName === 'TFOOT')).toBe(false);
        expect(mounted.elements.some((element) => element.getAttribute('role') === 'status')).toBe(true);
    });

    it('keeps totals unavailable after a partial-repair response and restores them after the final repair', async () => {
        const first = foreignLine(1);
        const remaining = foreignLine(2);
        const mounted = await mount({ items: [first, remaining], totals: null });
        api.deleteLine.mockResolvedValueOnce({ items: [remaining], totals: null });
        const remove = mounted.elements.find((element) => element.tagName === 'BUTTON'
            && element.getAttribute('aria-label') === en.DealsLineItems.remove);
        if (!remove) throw new Error('Remove line button was not rendered');

        await act(async () => mounted.dispatch('click', remove));

        expect(api.deleteLine).toHaveBeenCalledWith(7, first.id);
        expect(api.refresh).toHaveBeenCalledOnce();
        expect(mounted.container.textContent).toContain(en.DealsLineItems.totalsUnavailable);
        expect(mounted.container.textContent).toContain('¥100,000');
        expect(mounted.container.textContent).not.toContain('$100,000.00');
        expect(mounted.container.textContent).not.toContain('$0.00');

        api.deleteLine.mockResolvedValueOnce({
            items: [],
            totals: { currency: null, subtotal: 0, tax: 0, oneTimeTotal: 0, recurringTotal: 0, grandTotal: 0 },
        });
        const lastRemove = mounted.elements.find((element) => element.tagName === 'BUTTON'
            && element.getAttribute('aria-label') === en.DealsLineItems.remove
            && element !== remove);
        if (!lastRemove) throw new Error('Remaining line button was not rendered');
        await act(async () => mounted.dispatch('click', lastRemove));

        expect(api.deleteLine).toHaveBeenLastCalledWith(7, remaining.id);
        expect(mounted.container.textContent).not.toContain(en.DealsLineItems.totalsUnavailable);
        expect(mounted.container.textContent).toContain(en.DealsLineItems.empty);
    });
});
