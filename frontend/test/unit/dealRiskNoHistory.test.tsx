import { renderToStaticMarkup } from 'react-dom/server';
import { NextIntlClientProvider } from 'next-intl';
import { describe, expect, it } from 'vitest';

import DealRiskPanel from '@/app/components/records/deals/DealRiskPanel';
import type { DealRisk } from '@/app/lib/types';
import en from '@/messages/en/deals.json';
import ja from '@/messages/ja/deals.json';

const clear: DealRisk = {
    dealId: 1, value: 100, currency: 'JPY', level: 'none', score: 0,
    factors: [], assessedAt: '2026-11-05 00:00:00',
};

for (const [locale, messages] of [['en', en], ['ja', ja]] as const) {
    describe(`deal risk without relationship history (${locale})`, () => {
        it('shows a neutral first-interaction nudge alongside a clear assessment', () => {
            const markup = renderToStaticMarkup(
                <NextIntlClientProvider locale={locale} timeZone="UTC" messages={messages}>
                    <DealRiskPanel risk={clear} hasNoHistoryStakeholders />
                </NextIntlClientProvider>,
            );
            expect(markup).toContain(messages.DealRisk.clear);
            expect(markup).toContain(messages.DealRisk.noHistoryStakeholders);
            expect(markup).not.toContain(messages.DealRisk.factorStakeholderCold);
        });

        it('keeps unavailable evidence distinct and only nudges when history is missing', () => {
            for (const risk of [clear, null]) {
                const markup = renderToStaticMarkup(
                    <NextIntlClientProvider locale={locale} timeZone="UTC" messages={messages}>
                        <DealRiskPanel risk={risk} />
                    </NextIntlClientProvider>,
                );
                expect(markup).not.toContain(messages.DealRisk.noHistoryStakeholders);
                expect(markup).toContain(risk ? messages.DealRisk.clear : messages.DealRisk.unavailable);
            }
        });
    });
}
