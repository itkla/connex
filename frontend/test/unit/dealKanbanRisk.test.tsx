/** @vitest-environment jsdom */
import { act } from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import DealKanbanCard from '@/app/components/records/deals/DealKanbanCard';
import { TooltipProvider } from '@/components/ui/tooltip';
import type { Deal, DealRisk } from '@/app/lib/types';
import en from '@/messages/en/deals.json';
import ja from '@/messages/ja/deals.json';

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));
globalThis.IS_REACT_ACT_ENVIRONMENT = true;
beforeEach(() => {
    vi.stubGlobal('ResizeObserver', class {
        observe() {}
        unobserve() {}
        disconnect() {}
    });
});
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

const deal: Deal = { id: 1, name: 'Example', pipeline: 1, stage: 10, position: 0, value: 100, actualValue: 0, currency: 'JPY', company: null, createdAt: '', updatedAt: '' };
const risk: DealRisk = { dealId: 1, level: 'high', score: 90, factors: [{ code: 'no_stakeholders', severity: 'high', params: {} }], assessedAt: '2026-11-05T00:00:00Z', value: 100, currency: 'JPY' };

for (const locale of ['en', 'ja'] as const) {
    const messages = locale === 'en' ? en : ja;
    it(`${locale}: keeps the risk trigger above the stretched link and opens its evidence on keyboard focus`, async () => {
        render(<NextIntlClientProvider locale={locale} timeZone="UTC" messages={messages}>
            <TooltipProvider>
                <DealKanbanCard timezone="UTC" deal={deal} risk={risk} />
            </TooltipProvider>
        </NextIntlClientProvider>);
        const trigger = screen.getByText(messages.DealRisk.levelHigh);
        expect(trigger.tabIndex).toBe(0);
        expect(trigger.closest('a')).toBeNull();
        expect(trigger.classList.contains('relative')).toBe(true);
        expect(trigger.classList.contains('z-10')).toBe(true);
        expect(trigger.classList.contains('focus-visible:outline-2')).toBe(true);
        await act(async () => trigger.focus());
        expect(document.activeElement).toBe(trigger);
        expect((await screen.findByRole('tooltip')).textContent).toContain(messages.DealRisk.factorNoStakeholders);
    });
}
