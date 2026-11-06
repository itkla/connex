/** @vitest-environment jsdom */
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import { afterEach, describe, expect, it, vi } from 'vitest';
import DealCurrencyScope from '@/app/components/records/deals/DealCurrencyScope';
import DashboardCurrencyScope from '@/app/components/dashboard/DashboardCurrencyScope';
import en from '@/messages/en/records.json';
import ja from '@/messages/ja/records.json';
import enAnalytics from '@/messages/en/analytics.json';
import jaAnalytics from '@/messages/ja/analytics.json';

const push = vi.hoisted(() => vi.fn());
vi.mock('next/navigation', () => ({
    useRouter: () => ({ push }),
    usePathname: () => '/dashboard',
    useSearchParams: () => new URLSearchParams('keep=1'),
}));

afterEach(() => { cleanup(); vi.clearAllMocks(); });

const counts = new Map([['JPY', 1500], ['USD', 400], ['EUR', 100]]);

describe('explicit currency scope', () => {
    it.each(['en', 'ja'] as const)('shows the active currency, excluded count, and records link in %s', (locale) => {
        render(<NextIntlClientProvider locale={locale} messages={locale === 'ja' ? { ...ja, ...jaAnalytics } : { ...en, ...enAnalytics }}>
            <DealCurrencyScope dashboard counts={counts} currency="JPY" onChange={vi.fn()} recordsHref="/records/deals?currency=JPY" />
        </NextIntlClientProvider>);
        const region = screen.getByRole('region');
        expect(region.textContent).toContain('JPY');
        expect(region.textContent).toContain('500');
        expect(region.textContent).not.toContain('2,000');
        expect(screen.getByRole('link').getAttribute('href')).toBe('/records/deals?currency=JPY');
        expect(screen.getByRole('button').textContent).toBe('JPY');
    });

    it('switches the dashboard through navigation while preserving other URL parameters', async () => {
        render(<NextIntlClientProvider locale="en" messages={{ ...en, ...enAnalytics }}>
            <DashboardCurrencyScope counts={counts} currency="JPY" />
        </NextIntlClientProvider>);
        fireEvent.pointerDown(screen.getByRole('button'), { button: 0, ctrlKey: false, pointerType: 'mouse' });
        const usd = await screen.findByRole('menuitem', { name: /USD/ });
        expect(usd.textContent).toContain('400');
        fireEvent.click(usd);
        expect(push).toHaveBeenCalledWith('/dashboard?keep=1&currency=USD', { scroll: false });
    });

    it('keeps a zero-match selected bucket visible and offers currencies with matching records', async () => {
        const change = vi.fn();
        render(<NextIntlClientProvider locale="en" messages={{ ...en, ...enAnalytics }}>
            <DealCurrencyScope counts={new Map([['USD', 2]])} currency="JPY" onChange={change} />
        </NextIntlClientProvider>);
        expect(screen.getByText(/2 deals in other currencies/).textContent).toContain('Switch currency');
        fireEvent.pointerDown(screen.getByRole('button'), { button: 0, ctrlKey: false, pointerType: 'mouse' });
        fireEvent.click(await screen.findByRole('menuitem', { name: /USD/ }));
        expect(change).toHaveBeenCalledWith('USD');
    });
});
